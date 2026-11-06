package android.net

import android.os.Parcel

class DummyUri(private val str: String = "content://test/1") : Uri() {
    override fun isHierarchical(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = "content"
    override fun getSchemeSpecificPart(): String = "//test/1"
    override fun getEncodedSchemeSpecificPart(): String = "//test/1"
    override fun getAuthority(): String = "test"
    override fun getEncodedAuthority(): String = "test"
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = "test"
    override fun getPort(): Int = -1
    override fun getPath(): String = "/1"
    override fun getEncodedPath(): String = "/1"
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = listOf("1")
    override fun getLastPathSegment(): String = "1"
    override fun buildUpon(): Builder? = null
    override fun toString(): String = str
    override fun compareTo(other: Uri?): Int = str.compareTo(other?.toString().orEmpty())
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {}
    override fun equals(other: Any?): Boolean = other is Uri && toString() == other.toString()
    override fun hashCode(): Int = str.hashCode()
}
