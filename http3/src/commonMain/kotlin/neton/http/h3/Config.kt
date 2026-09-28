package neton.http.h3

import neton.http.h3.proto.SettingId
import neton.http.h3.proto.VarInt
import neton.http.h3.qpack.DEFAULT_MAX_FIELD_COUNT
import neton.http.h3.qpack.DEFAULT_MAX_FIELD_SECTION_SIZE
import neton.http.h3.proto.Settings as SettingsFrame

// Connection configuration and HTTP/3 settings (`h3::config`, `src/config.rs`).

/**
 * HTTP/3 settings (`config::Settings`): the values an endpoint advertises in its SETTINGS frame, or the peer's, as
 * received ([Settings.fromFrame]).
 *
 * ⛔ WebTransport, extended CONNECT and HTTP Datagrams are not in the first version (SPEC §5): locally they are always
 * off (advertised as 0, as the reference does by default); the peer's values are recorded but not acted on.
 */
class Settings internal constructor(
    /** SETTINGS_MAX_FIELD_SECTION_SIZE: the largest decoded field section the endpoint accepts (name + value + 32). */
    val maxFieldSectionSize: Long,
    /** SETTINGS_ENABLE_WEBTRANSPORT (draft-ietf-webtrans-http3). */
    val enableWebtransport: Boolean = false,
    /** SETTINGS_ENABLE_CONNECT_PROTOCOL (RFC 9220). */
    val enableExtendedConnect: Boolean = false,
    /** SETTINGS_H3_DATAGRAM (RFC 9297). */
    val enableDatagram: Boolean = false,
    /** SETTINGS_WEBTRANSPORT_MAX_SESSIONS. */
    val maxWebtransportSessions: Long = 0,
) {
    override fun toString(): String = "Settings(maxFieldSectionSize=$maxFieldSectionSize, " +
        "enableWebtransport=$enableWebtransport, enableExtendedConnect=$enableExtendedConnect, " +
        "enableDatagram=$enableDatagram, maxWebtransportSessions=$maxWebtransportSessions)"

    companion object {
        /**
         * The peer's settings before its SETTINGS frame arrives (`Settings::default`, RFC 9114 §7.2.4.2): no limit on
         * the field section size (the RFC's default is unlimited), everything else off.
         */
        val DEFAULT: Settings = Settings(VarInt.MAX)

        /** The settings of a received SETTINGS frame (`From<&frame::Settings>`); absent entries keep the defaults. */
        fun fromFrame(frame: SettingsFrame): Settings = Settings(
            maxFieldSectionSize = frame.get(SettingId.MAX_HEADER_LIST_SIZE) ?: DEFAULT.maxFieldSectionSize,
            enableWebtransport = frame.get(SettingId.ENABLE_WEBTRANSPORT)?.let { it != 0L } ?: false,
            enableExtendedConnect = frame.get(SettingId.ENABLE_CONNECT_PROTOCOL)?.let { it != 0L } ?: false,
            enableDatagram = frame.get(SettingId.H3_DATAGRAM)?.let { it != 0L } ?: false,
            maxWebtransportSessions = frame.get(SettingId.WEBTRANSPORT_MAX_SESSIONS) ?: 0,
        )
    }
}

/**
 * The configuration of an HTTP/3 connection (`Config`), set through the server and client builders.
 *
 * Defaults: [sendGrease] true (the reference's); the three header limits of SPEC §5 (⚖️ the reference has only
 * `max_field_section_size`, unlimited by default): [maxHeadersFrameSize] 64 KiB of encoded HEADERS payload,
 * [maxFieldSectionSize] 64 KiB of decoded field section (advertised as SETTINGS_MAX_FIELD_SECTION_SIZE),
 * [maxFieldCount] 100 field lines. The QPACK dynamic table capacity is 0 (SPEC §5; the SETTINGS default, not sent).
 */
class Config {
    /** Sends GREASE: a reserved setting, one reserved stream per connection, one reserved frame per connection. */
    var sendGrease: Boolean = true
        internal set

    /** The largest decoded field section accepted, and advertised to the peer (SETTINGS_MAX_FIELD_SECTION_SIZE). */
    var maxFieldSectionSize: Long = DEFAULT_MAX_FIELD_SECTION_SIZE
        internal set

    /** The largest encoded HEADERS frame payload accepted; a larger one is refused from its frame header. */
    var maxHeadersFrameSize: Int = DEFAULT_MAX_HEADERS_FRAME_SIZE
        internal set

    /** The most field lines accepted in one field section. */
    var maxFieldCount: Int = DEFAULT_MAX_FIELD_COUNT
        internal set

    /** Test hook (the reference's `#[cfg(test)] send_settings`): false leaves the local streams unwritten. */
    internal var sendSettings: Boolean = true

    /** The local settings (`config.settings`). */
    val settings: Settings get() = Settings(maxFieldSectionSize)

    internal fun copy(): Config = Config().also {
        it.sendGrease = sendGrease
        it.maxFieldSectionSize = maxFieldSectionSize
        it.maxHeadersFrameSize = maxHeadersFrameSize
        it.maxFieldCount = maxFieldCount
        it.sendSettings = sendSettings
    }

    /**
     * The local SETTINGS frame (`TryFrom<Config> for frame::Settings`): a GREASE setting when [sendGrease] (RFC 9114
     * §7.2.4.1), SETTINGS_MAX_FIELD_SECTION_SIZE, and the extension settings at 0 (as the reference sends them).
     */
    internal fun toFrame(): SettingsFrame {
        val s = SettingsFrame()
        if (sendGrease) s.insert(SettingId.grease(), 0)
        s.insert(SettingId.MAX_HEADER_LIST_SIZE, maxFieldSectionSize)
        s.insert(SettingId.ENABLE_CONNECT_PROTOCOL, 0)
        s.insert(SettingId.ENABLE_WEBTRANSPORT, 0)
        s.insert(SettingId.H3_DATAGRAM, 0)
        s.insert(SettingId.WEBTRANSPORT_MAX_SESSIONS, 0)
        return s
    }

    override fun toString(): String = "Config(sendGrease=$sendGrease, maxFieldSectionSize=$maxFieldSectionSize, " +
        "maxHeadersFrameSize=$maxHeadersFrameSize, maxFieldCount=$maxFieldCount)"
}
