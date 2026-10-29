package io.leancast.domain.ssdp

import java.util.UUID

/**
 * Derives the renderer UDN from a device-scoped identifier.
 *
 * App-private storage is wiped on uninstall, so a randomly generated UUID would
 * make every reinstall look like a brand new device to cast senders. Deriving it
 * from the platform `ANDROID_ID` keeps the identity stable across reinstalls
 * while still giving each physical device its own value.
 */
object DeviceUuid {

    /**
     * Namespacing salt. `ANDROID_ID` is already scoped per signing key, user and
     * device on API 26+, and this prefix keeps the result from being a bare hash
     * of the raw id.
     */
    private const val SALT = "io.leancast:"

    /**
     * Values devices are known to report when they have no usable id: the empty
     * string, the literal `"null"` some OEM builds return, and the shared
     * `9774d56d682e549c` that shipped on a batch of early Android devices.
     */
    private val UNUSABLE_IDS = setOf("null", "unknown", "9774d56d682e549c")

    /**
     * Derives a `uuid:`-prefixed UDN from [deviceId], or `null` when [deviceId]
     * is absent or unusable and the caller should fall back to a random UUID.
     *
     * The same [deviceId] always yields the same UDN (this is what makes the
     * identity survive a reinstall); different ids yield different ones.
     */
    fun derive(deviceId: String?): String? {
        val trimmed = deviceId?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.lowercase() in UNUSABLE_IDS) return null
        val name = "$SALT$trimmed"
        return "uuid:${UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8))}"
    }
}
