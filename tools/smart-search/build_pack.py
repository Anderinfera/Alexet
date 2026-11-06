"""Builds the Alexet smart search model pack (siglip2-b16-224.alexetai).

The pack holds SigLIP 2 ViT-B/16 (224 px, Apache-2.0) split for on-device use:
  image_encoder.tflite   litert-community fp16 image tower, copied as is
  text_encoder.tflite    text tower without its token lookup: [1,64,768] embeddings in,
                         [1,768] L2-normalized embedding out
  token_embeddings.i8    token embedding table, int8, one row per token
  token_scales.f32       one float32 scale per row (row = int8 * scale)
  vocab.bin              SentencePiece pieces (Gemma tokenizer) in a flat format
  selftest.bin           synthetic images and queries with reference outputs
  manifest.json, LICENSE, NOTICE

Each step is checked against the original Hugging Face model; the script stops on a miss.
golden_tokens.json (token ids from the Hugging Face tokenizer) is written next to the pack
for the app's unit tests.

litert-converter only ships Linux x86_64 and macOS arm64 wheels; on Windows run it in WSL:
    python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
    .venv/bin/python build_pack.py [--out DIR]
"""
import argparse
import hashlib
import heapq
import importlib.metadata
import json
import os
import struct
import sys
import zipfile

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

HF_MODEL = "google/siglip2-base-patch16-224"
HF_REVISION = "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2"
LITERT_REPO = "litert-community/SigLIP2-base-patch16-224"
LITERT_FILE = "siglip2_base_224_fp16.tflite"
LITERT_REVISION = "509b5cbcf1a849f37696be08f8297c6cd3050bf4"

MODEL_ID = "siglip2-b16-224-v1"
PACK_NAME = "siglip2-b16-224.alexetai"
SEQ = 64
DIM = 768
IMG = 224
PACK_FORMAT = 1
MIN_COS = 0.999

QUERIES = [
    "pies na trawie", "kot śpiący na kanapie", "zachód słońca nad morzem",
    "dzieci bawiące się w parku", "tort urodzinowy ze świeczkami", "góry zimą",
    "samochód w nocy", "zrzut ekranu z czatu", "dokument z tekstem", "jedzenie na talerzu",
    "wesele", "plaża", "las jesienią", "selfie w lustrze", "ręcznie pisana notatka",
    "paragon", "kwiaty w wazonie", "pociąg na stacji", "śnieg na ulicy", "koncert w nocy",
    "a dog on the grass", "a cat sleeping on a couch", "sunset over the sea",
    "birthday cake with candles", "mountains in winter", "a car at night",
    "screenshot of a chat", "a plate of food", "a beach", "autumn forest",
    "a selfie in the mirror", "handwritten note", "a receipt", "flowers in a vase",
    "a train at the station", "snow on the street", "a concert",
    "a red circle", "a blue square", "a green triangle",
]

# Tokenizer-only strings: case, digits, spacing, emoji, scripts, byte fallback, truncation.
TOKENIZER_EXTRA = [
    "Pies NA Trawie", "ZAŻÓŁĆ GĘŚLĄ JAŹŃ", "zdjęcia z 2024 r.", "12:45, 3.14 i 1000000",
    "  dwie  spacje  ", "\tz tabulatorem\n i nową linią", "🐶🐱 emoji", "東京タワー",
    "Ελληνικά ΟΔΟΣ", "İstanbul", " prywatny znak", "𝔘𝔫𝔦𝔠𝔬𝔡𝔢",
    "<b>html</b> &amp;", "a" * 300, "słowo " * 80, "", " ",
]

SHAPES = [
    ("red", "circle", "czerwone koło"),
    ("blue", "square", "niebieski kwadrat"),
    ("green", "triangle", "zielony trójkąt"),
]


def log(msg):
    print(msg, flush=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cos(a, b):
    a = np.asarray(a, dtype=np.float64).reshape(-1)
    b = np.asarray(b, dtype=np.float64).reshape(-1)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))


def tensor_of(out):
    """get_*_features returns a tensor in older transformers, a model output in newer ones."""
    if isinstance(out, torch.Tensor):
        return out
    return out.pooler_output


# ----------------------------------------------------------------------------- tokenizer

PIECE_NORMAL, PIECE_UNKNOWN, PIECE_CONTROL, PIECE_USER, PIECE_UNUSED, PIECE_BYTE = 1, 2, 3, 4, 5, 6


def load_sp_model(path):
    from sentencepiece import sentencepiece_model_pb2 as sp_pb2

    m = sp_pb2.ModelProto()
    with open(path, "rb") as f:
        m.ParseFromString(f.read())
    return m


def check_sp_model(m):
    ns, ts = m.normalizer_spec, m.trainer_spec
    facts = {
        "model_type": ts.model_type,
        "byte_fallback": ts.byte_fallback,
        "normalizer": ns.name,
        "precompiled_charsmap": len(ns.precompiled_charsmap),
        "add_dummy_prefix": ns.add_dummy_prefix,
        "remove_extra_whitespaces": ns.remove_extra_whitespaces,
        "escape_whitespaces": ns.escape_whitespaces,
        "treat_whitespace_as_suffix": ts.treat_whitespace_as_suffix,
    }
    log(f"sentencepiece spec: {facts}")
    # The app's tokenizer implements BPE with an identity normalizer only.
    assert ts.model_type == 2, "expected a BPE model"
    assert ns.name == "identity" and facts["precompiled_charsmap"] == 0, "normalizer is not identity"
    assert not ts.treat_whitespace_as_suffix
    return facts


def write_vocab(m, facts, path):
    """Format (little endian): 'AXVB', u32 version, u32 count, u32 flags, i32 unk id,
    then per piece: u8 type, f32 score, u16 byte length, UTF-8 bytes."""
    flags = (
        (1 if facts["byte_fallback"] else 0)
        | (2 if facts["add_dummy_prefix"] else 0)
        | (4 if facts["remove_extra_whitespaces"] else 0)
        | (8 if facts["escape_whitespaces"] else 0)
    )
    unk = next(i for i, p in enumerate(m.pieces) if p.type == PIECE_UNKNOWN)
    with open(path, "wb") as f:
        f.write(b"AXVB")
        f.write(struct.pack("<IIIi", 1, len(m.pieces), flags, unk))
        for p in m.pieces:
            b = p.piece.encode("utf-8")
            f.write(struct.pack("<BfH", p.type, p.score, len(b)))
            f.write(b)


class RefTokenizer:
    """SentencePiece BPE encoding as the app implements it (mirrors bpe_model.cc)."""

    def __init__(self, m, facts):
        self.facts = facts
        self.pieces = {}  # NORMAL / USER_DEFINED / UNUSED: what merges may produce
        self.all_ids = {}
        self.unused = set()
        self.user = set()
        for i, p in enumerate(m.pieces):
            self.all_ids.setdefault(p.piece, i)
            if p.type in (PIECE_NORMAL, PIECE_USER, PIECE_UNUSED):
                self.pieces[p.piece] = (i, p.score)
            if p.type == PIECE_UNUSED:
                self.unused.add(i)
            if p.type == PIECE_USER:
                self.user.add(p.piece)
            if p.type == PIECE_UNKNOWN:
                self.unk = i
        self.max_user = max((len(u) for u in self.user), default=0)
        self.bytes = {}
        for i, p in enumerate(m.pieces):
            if p.type == PIECE_BYTE:
                self.bytes[int(p.piece[3:5], 16)] = i

    def normalize(self, text):
        f = self.facts
        if f["remove_extra_whitespaces"]:
            text = " ".join(text.strip(" ").split(" "))
            while "  " in text:
                text = text.replace("  ", " ")
        if not text:
            return ""
        if f["add_dummy_prefix"]:
            text = " " + text
        if f["escape_whitespaces"]:
            text = text.replace(" ", "▁")
        return text

    def encode(self, text):
        s = self.normalize(text)
        syms = []
        i = 0
        while i < len(s):
            match = None
            for n in range(min(self.max_user, len(s) - i), 0, -1):
                if s[i:i + n] in self.user:
                    match = s[i:i + n]
                    break
            if match is not None:
                syms.append([match, True])
                i += len(match)
            else:
                syms.append([s[i], False])
                i += 1
        n = len(syms)
        prev = list(range(-1, n - 1))
        nxt = list(range(1, n + 1))
        if n:
            nxt[-1] = -1
        agenda = []
        rev_merge = {}

        def add_pair(left, right):
            if left < 0 or right < 0 or syms[left][1] or syms[right][1]:
                return
            piece = syms[left][0] + syms[right][0]
            hit = self.pieces.get(piece)
            if hit is None:
                return
            pid, score = hit
            # Highest score first; on a tie, the leftmost pair.
            heapq.heappush(agenda, (-score, left, right, len(piece)))
            if pid in self.unused:
                rev_merge[piece] = (syms[left][0], syms[right][0])

        for k in range(1, n):
            add_pair(k - 1, k)
        while agenda:
            _, left, right, size = heapq.heappop(agenda)
            if not syms[left][0] or not syms[right][0]:
                continue
            if len(syms[left][0]) + len(syms[right][0]) != size:
                continue
            syms[left][0] = syms[left][0] + syms[right][0]
            syms[right][0] = ""
            nxt[left] = nxt[right]
            if nxt[right] >= 0:
                prev[nxt[right]] = left
            add_pair(prev[left], left)
            add_pair(left, nxt[left])

        out = []

        def emit(piece):
            pid = self.all_ids.get(piece)
            if pid is not None and pid not in self.unused:
                out.append(pid)
                return
            if piece in rev_merge:
                a, b = rev_merge[piece]
                emit(a)
                emit(b)
                return
            if self.facts["byte_fallback"]:
                out.extend(self.bytes[b] for b in piece.encode("utf-8"))
            else:
                out.append(self.unk)

        k = 0 if n else -1
        while k >= 0:
            if syms[k][0]:
                emit(syms[k][0])
            k = nxt[k]
        return out

    def encode_padded(self, text, pad_id, eos_id):
        ids = self.encode(text.lower())[: SEQ - 1] + [eos_id]
        return ids + [pad_id] * (SEQ - len(ids))


def hf_ids(tok, texts):
    enc = tok([t.lower() for t in texts], padding="max_length", max_length=SEQ, truncation=True)
    return [list(x) for x in enc["input_ids"]]


# ----------------------------------------------------------------------------- text tower

class TextTower(nn.Module):
    """SigLIP text tower from token embeddings on: + positions, encoder, final LN,
    last position (the model is trained on max_length padding), head, L2."""

    def __init__(self, text_model):
        super().__init__()
        self.tm = text_model
        self.register_buffer(
            "pos", text_model.embeddings.position_embedding.weight[:SEQ].detach().clone().unsqueeze(0)
        )

    def forward(self, inputs_embeds):
        h = inputs_embeds + self.pos
        h = self.tm.encoder(inputs_embeds=h)
        h = h.last_hidden_state if hasattr(h, "last_hidden_state") else h[0]
        h = self.tm.final_layer_norm(h)
        p = self.tm.head(h[:, -1, :])
        return F.normalize(p, dim=-1)


def quantize_table(weight):
    w = weight.detach().float().numpy()
    scale = np.abs(w).max(axis=1) / 127.0
    scale[scale == 0] = 1.0
    q = np.clip(np.rint(w / scale[:, None]), -127, 127).astype(np.int8)
    return q, scale.astype(np.float32)


def embed_rows(q, scale, ids):
    ids = np.asarray(ids)
    return (q[ids].astype(np.float32) * scale[ids][:, None])[None, :, :]


def fp16_cast(src, dst):
    from ai_edge_quantizer import quantizer, recipe_manager
    from ai_edge_quantizer.recipe import AlgorithmName, qtyping

    rm = recipe_manager.RecipeManager()
    rm.add_quantization_config(
        regex=".*",
        operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
        op_config=qtyping.OpQuantizationConfig(
            weight_tensor_config=qtyping.TensorQuantizationConfig(
                num_bits=16, dtype=qtyping.TensorDataType.FLOAT),
            compute_precision=qtyping.ComputePrecision.FLOAT,
        ),
        algorithm_key=AlgorithmName.FLOAT_CASTING,
    )
    if os.path.exists(dst):
        os.remove(dst)
    qt = quantizer.Quantizer(float_model=src)
    qt.load_quantization_recipe(rm.get_quantization_recipe())
    qt.quantize().export_model(dst)


class Tflite:
    def __init__(self, path):
        from ai_edge_litert.interpreter import Interpreter

        self.it = Interpreter(model_path=path)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]

    def __call__(self, x):
        self.it.set_tensor(self.inp["index"], np.ascontiguousarray(x, dtype=self.inp["dtype"]))
        self.it.invoke()
        return self.it.get_tensor(self.out["index"]).astype(np.float32).reshape(-1)


# ----------------------------------------------------------------------------- images

def shape_image(color, shape):
    from PIL import Image, ImageDraw

    img = Image.new("RGB", (IMG, IMG), "white")
    d = ImageDraw.Draw(img)
    box = (40, 40, IMG - 40, IMG - 40)
    if shape == "circle":
        d.ellipse(box, fill=color)
    elif shape == "square":
        d.rectangle(box, fill=color)
    else:
        d.polygon([(IMG // 2, 30), (IMG - 30, IMG - 40), (30, IMG - 40)], fill=color)
    return np.asarray(img, dtype=np.uint8)


def to_pixels(rgb):
    """224x224 RGB uint8 -> [1,3,224,224] float32 in [-1,1], as SiglipImageProcessor does."""
    x = rgb.astype(np.float32) / 255.0
    x = (x - 0.5) / 0.5
    return np.transpose(x, (2, 0, 1))[None].copy()


# ----------------------------------------------------------------------------- main

def resolve_revision(repo, rev):
    from huggingface_hub import HfApi

    return HfApi().model_info(repo, revision=rev).sha


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(here, "out"))
    args = ap.parse_args()
    out = args.out
    work = os.path.join(out, "work")
    pack_dir = os.path.join(out, "pack")
    os.makedirs(work, exist_ok=True)
    os.makedirs(pack_dir, exist_ok=True)
    torch.manual_seed(0)

    from huggingface_hub import hf_hub_download
    from transformers import AutoModel, AutoTokenizer

    hf_rev = resolve_revision(HF_MODEL, HF_REVISION)
    lt_rev = resolve_revision(LITERT_REPO, LITERT_REVISION)
    log(f"{HF_MODEL} @ {hf_rev}\n{LITERT_REPO} @ {lt_rev}")

    model = AutoModel.from_pretrained(HF_MODEL, revision=hf_rev, attn_implementation="eager").eval()
    tok = AutoTokenizer.from_pretrained(HF_MODEL, revision=hf_rev)
    pad_id, eos_id = tok.pad_token_id, tok.eos_token_id
    log(f"pad {pad_id} eos {eos_id} logit_scale {model.logit_scale.exp().item():.4f} "
        f"logit_bias {model.logit_bias.item():.4f}")

    # --- tokenizer
    sp = load_sp_model(hf_hub_download(HF_MODEL, "tokenizer.model", revision=hf_rev))
    facts = check_sp_model(sp)
    vocab = os.path.join(pack_dir, "vocab.bin")
    write_vocab(sp, facts, vocab)
    ref = RefTokenizer(sp, facts)
    texts = QUERIES + TOKENIZER_EXTRA + [s for _, _, s in SHAPES]
    expected = hf_ids(tok, texts)
    misses = [(t, e, ref.encode_padded(t, pad_id, eos_id)) for t, e in zip(texts, expected)
              if ref.encode_padded(t, pad_id, eos_id) != e]
    for t, e, g in misses[:5]:
        log(f"TOKEN MISS {t!r}\n  hf  {e}\n  ref {g}")
    assert not misses, f"{len(misses)} tokenizer mismatches"
    log(f"tokenizer: {len(texts)} strings identical to Hugging Face")
    with open(os.path.join(out, "golden_tokens.json"), "w", encoding="utf-8") as f:
        json.dump({"padId": pad_id, "eosId": eos_id, "seqLen": SEQ,
                   "cases": [{"text": t, "ids": e} for t, e in zip(texts, expected)]},
                  f, ensure_ascii=False, indent=1)

    # --- reference outputs (fp32, Hugging Face)
    with torch.no_grad():
        ref_text = F.normalize(tensor_of(model.get_text_features(
            input_ids=torch.tensor(hf_ids(tok, QUERIES)))), dim=-1).numpy()

    # --- token table
    table = model.text_model.embeddings.token_embedding.weight
    assert table.shape[1] == DIM
    q, scale = quantize_table(table)
    q.tofile(os.path.join(pack_dir, "token_embeddings.i8"))
    scale.astype("<f4").tofile(os.path.join(pack_dir, "token_scales.f32"))

    # --- text tower
    tower = TextTower(model.text_model).eval()
    ids = hf_ids(tok, QUERIES)
    with torch.no_grad():
        eager = [tower(torch.from_numpy(embed_rows(q, scale, i))).numpy() for i in ids]
    worst = min(cos(a, b) for a, b in zip(eager, ref_text))
    log(f"text tower (torch, int8 table) vs HF: worst cos {worst:.6f}")
    assert worst >= MIN_COS, "text tower wrapper or int8 table changes the output"

    import litert_torch

    fp32_path = os.path.join(work, "text_encoder_fp32.tflite")
    text_path = os.path.join(pack_dir, "text_encoder.tflite")
    sample = torch.from_numpy(embed_rows(q, scale, ids[0]))
    litert_torch.convert(tower, (sample,)).export(fp32_path)
    fp16_cast(fp32_path, text_path)
    text_model = Tflite(text_path)
    got = [text_model(embed_rows(q, scale, i)) for i in ids]
    worst = min(cos(a, b) for a, b in zip(got, ref_text))
    log(f"text_encoder.tflite (fp16) vs HF: worst cos {worst:.6f}, "
        f"{os.path.getsize(text_path) / 1e6:.1f} MB")
    assert worst >= MIN_COS, "converted text tower differs from Hugging Face"

    # --- image tower
    image_path = os.path.join(pack_dir, "image_encoder.tflite")
    src = hf_hub_download(LITERT_REPO, LITERT_FILE, revision=lt_rev)
    with open(src, "rb") as a, open(image_path, "wb") as b:
        b.write(a.read())
    image_model = Tflite(image_path)
    imgs = [shape_image(c, s) for c, s, _ in SHAPES]
    rng = np.random.default_rng(0)
    imgs.append(rng.integers(0, 256, (IMG, IMG, 3), dtype=np.uint8))
    with torch.no_grad():
        ref_img = [F.normalize(tensor_of(model.get_image_features(
            pixel_values=torch.from_numpy(to_pixels(x)))), dim=-1).numpy().reshape(-1) for x in imgs]
    got_img = [image_model(to_pixels(x)) for x in imgs]
    worst = min(cos(a, b) for a, b in zip(got_img, ref_img))
    log(f"image_encoder.tflite vs HF image tower: worst cos {worst:.6f}")
    assert worst >= MIN_COS, "litert image tower is not the same model as the HF checkpoint"

    # --- ranking: shapes, English and Polish queries
    shape_queries = [f"a {c} {s}" for c, s, _ in SHAPES] + [pl for _, _, pl in SHAPES]
    sq_ids = hf_ids(tok, shape_queries)
    with torch.no_grad():
        hf_sq = F.normalize(tensor_of(model.get_text_features(input_ids=torch.tensor(sq_ids))), dim=-1).numpy()
    ours_sq = np.stack([text_model(embed_rows(q, scale, i)) for i in sq_ids])
    hf_rank = (hf_sq @ np.stack(ref_img[:3]).T).argmax(1)
    our_rank = (ours_sq @ np.stack(got_img[:3]).T).argmax(1)
    log(f"shape ranking hf {hf_rank.tolist()} ours {our_rank.tolist()} (ideal [0,1,2,0,1,2])")
    assert (hf_rank == our_rank).all(), "ranking differs from Hugging Face"

    # --- self test for the phone
    selftest = os.path.join(pack_dir, "selftest.bin")
    st_queries = shape_queries[:3] + ["pies na trawie", "zachód słońca nad morzem"]
    st_ids = hf_ids(tok, st_queries)
    with torch.no_grad():
        st_ref = F.normalize(tensor_of(model.get_text_features(input_ids=torch.tensor(st_ids))), dim=-1).numpy()
    with open(selftest, "wb") as f:
        # 'AXST', u32 version, u32 images, then per image: 224*224*3 RGB bytes, 768 f32;
        # u32 queries, then per query: u16 length, UTF-8, 64 i32 ids, 768 f32.
        f.write(b"AXST")
        f.write(struct.pack("<II", 1, len(imgs)))
        for x, e in zip(imgs, ref_img):
            f.write(x.tobytes())
            f.write(np.asarray(e, dtype="<f4").tobytes())
        f.write(struct.pack("<I", len(st_queries)))
        for t, i, e in zip(st_queries, st_ids, st_ref):
            b = t.encode("utf-8")
            f.write(struct.pack("<H", len(b)))
            f.write(b)
            f.write(np.asarray(i, dtype="<i4").tobytes())
            f.write(np.asarray(e, dtype="<f4").tobytes())

    # --- license, notice, manifest, pack
    write_license(os.path.join(pack_dir, "LICENSE"))
    write_notice(os.path.join(pack_dir, "NOTICE"), hf_rev, lt_rev)
    files = ["image_encoder.tflite", "text_encoder.tflite", "token_embeddings.i8",
             "token_scales.f32", "vocab.bin", "selftest.bin", "LICENSE", "NOTICE"]
    manifest = {
        "format": PACK_FORMAT,
        "modelId": MODEL_ID,
        "source": {"model": HF_MODEL, "revision": hf_rev, "image": LITERT_REPO, "imageRevision": lt_rev},
        "dim": DIM, "seqLen": SEQ, "imageSize": IMG, "vocabSize": int(table.shape[0]),
        "padId": pad_id, "eosId": eos_id, "lowercase": True,
        "logitScale": float(model.logit_scale.exp().item()),
        "logitBias": float(model.logit_bias.item()),
        "files": {n: {"size": os.path.getsize(os.path.join(pack_dir, n)),
                      "sha256": sha256(os.path.join(pack_dir, n))} for n in files},
    }
    with open(os.path.join(pack_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1)
    pack = os.path.join(out, PACK_NAME)
    with zipfile.ZipFile(pack, "w", compression=zipfile.ZIP_STORED) as z:
        for n in ["manifest.json"] + files:
            z.write(os.path.join(pack_dir, n), n)
    log(f"DONE {pack} {os.path.getsize(pack) / 1e6:.1f} MB")
    for n in files:
        log(f"  {n:22s} {manifest['files'][n]['size'] / 1e6:8.1f} MB")


def write_license(path):
    for dist in ("litert-torch", "ai-edge-litert", "transformers"):
        try:
            for f in importlib.metadata.files(dist) or []:
                if os.path.basename(str(f)).upper().startswith("LICENSE"):
                    text = f.read_text(encoding="utf-8")
                    if "Apache License" in text and "Version 2.0" in text:
                        with open(path, "w", encoding="utf-8") as out:
                            out.write(text)
                        return
        except importlib.metadata.PackageNotFoundError:
            continue
    sys.exit("Apache-2.0 license text not found in installed packages")


def write_notice(path, hf_rev, lt_rev):
    text = f"""Alexet smart search model pack ({MODEL_ID})

This pack contains machine learning models derived from SigLIP 2 (ViT-B/16, 224 px)
by Google LLC, https://huggingface.co/{HF_MODEL} (revision {hf_rev}),
licensed under the Apache License, Version 2.0 (see LICENSE).

image_encoder.tflite is an unmodified copy of {LITERT_FILE} from
https://huggingface.co/{LITERT_REPO} (revision {lt_rev}), a LiteRT conversion of the
SigLIP 2 image tower, licensed under the Apache License, Version 2.0.

Changes made for this pack:
- text_encoder.tflite: the SigLIP 2 text tower converted to LiteRT with litert-torch and its
  weights cast to float16; the token embedding lookup is moved out of the model.
- token_embeddings.i8, token_scales.f32: the text tower's token embedding table quantized to
  int8 with one float32 scale per row.
- vocab.bin: the SentencePiece vocabulary (Gemma tokenizer) of the same repository,
  re-encoded in a flat binary format.
- selftest.bin: synthetic test images and reference outputs.
"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


if __name__ == "__main__":
    main()
