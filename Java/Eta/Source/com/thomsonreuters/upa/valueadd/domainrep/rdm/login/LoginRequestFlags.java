package com.thomsonreuters.upa.valueadd.domainrep.rdm.login;

/**
 * The RDM login request flags.
 * 
 * @see LoginRequest
 */
public class LoginRequestFlags
{
    /** (0x0000) No flags set */
    public static final int NONE = 0x0000;

    /** (0x0001) Indicates presence of the attrib member. */
    public static final int HAS_ATTRIB = 0x0001;

    /** (0x0002) Indicates presence of the downloadConnectionConfig member */
    public static final int HAS_DOWNLOAD_CONN_CONFIG = 0x0002;

    /** (0x0004) Indicates presence of the instanceId member */
    public static final int HAS_INSTANCE_ID = 0x0004;

    /** (0x0008) Indicates presence of the password member */
    public static final int HAS_PASSWORD = 0x0008;

    /** (0x0010) Indicates presence of the role member */
    public static final int HAS_ROLE = 0x0010;

    /** (0x0020) Indicates presence of the userNameType member */
    public static final int HAS_USERNAME_TYPE = 0x0020;

    /**
     * (0x0040) Indicates the Consumer or Non-Interactive provider does not
     * require a refresh.
     */
    public static final int NO_REFRESH = 0x0040;

    /**
     * (0x0080) Used by a Consumer to request that all open items on a channel
     * be paused. Support for this request is indicated by the
     * supportOptimizedPauseResume member of the {@link LoginRefresh}
     */
    public static final int PAUSE_ALL = 0x0080;

    /**
     * (0x0100) Inform a Provider that it can request dictionary.
     * Support for this request is indicated by the
     * supportProviderDictionaryDownload member of the {@link LoginAttrib}
     */
    public static final int HAS_PROVIDER_SUPPORT_DICTIONARY_DOWNLOAD = 0x0100;
        
    private LoginRequestFlags()
    {
        throw new AssertionError();
    }
}