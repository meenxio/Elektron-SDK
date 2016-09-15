package com.thomsonreuters.upa.perftools.upajprovperf;

import java.net.InetAddress;

import com.thomsonreuters.upa.codec.CodecFactory;
import com.thomsonreuters.upa.codec.CodecReturnCodes;
import com.thomsonreuters.upa.codec.DataStates;
import com.thomsonreuters.upa.codec.DecodeIterator;
import com.thomsonreuters.upa.codec.EncodeIterator;
import com.thomsonreuters.upa.codec.Msg;
import com.thomsonreuters.upa.codec.MsgClasses;
import com.thomsonreuters.upa.codec.StateCodes;
import com.thomsonreuters.upa.codec.StreamStates;
import com.thomsonreuters.upa.perftools.common.ChannelHandler;
import com.thomsonreuters.upa.perftools.common.ClientChannelInfo;
import com.thomsonreuters.upa.perftools.common.PerfToolsReturnCodes;
import com.thomsonreuters.upa.rdm.Login;
import com.thomsonreuters.upa.transport.Channel;
import com.thomsonreuters.upa.transport.Error;
import com.thomsonreuters.upa.transport.TransportBuffer;
import com.thomsonreuters.upa.transport.WritePriorities;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginMsgFactory;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginMsgType;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginRefresh;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginRequest;
import com.thomsonreuters.upa.valueadd.reactor.ReactorChannel;
import com.thomsonreuters.upa.valueadd.reactor.ReactorErrorInfo;
import com.thomsonreuters.upa.valueadd.reactor.ReactorFactory;
import com.thomsonreuters.upa.valueadd.reactor.ReactorSubmitOptions;

/**
 * Determines the information a provider needs for accepting logins, and
 * provides encoding of appropriate responses.
 */
public class LoginProvider
{
    private static final int      REFRESH_MSG_SIZE = 512;

    private LoginRefresh          _loginRefresh;
    private LoginRequest          _loginRequest;
    private EncodeIterator        _encodeIter;
    private String                _applicationId;
    private String                _applicationName;
    private String                _position;
    
    private ReactorErrorInfo      _errorInfo; // Use the VA Reactor instead of the UPA Channel for sending and receiving
    private ReactorSubmitOptions  _reactorSubmitOptions; // Use the VA Reactor instead of the UPA Channel for sending and receiving
    
    public LoginProvider()
    {
        _loginRefresh = (LoginRefresh)LoginMsgFactory.createMsg();
        _loginRequest = (LoginRequest)LoginMsgFactory.createMsg();
        _encodeIter = CodecFactory.createEncodeIterator();
        _loginRefresh.rdmMsgType(LoginMsgType.REFRESH);
        _loginRequest.rdmMsgType(LoginMsgType.REQUEST);
        
        _errorInfo = ReactorFactory.createReactorErrorInfo();
        _reactorSubmitOptions = ReactorFactory.createReactorSubmitOptions();
        _reactorSubmitOptions.clear();
        _reactorSubmitOptions.writeArgs().priority(WritePriorities.HIGH);
    }

    /**
     * Processes a login request.
     * 
     * @param channelHandler - The channel handler
     * @param msg - The partially decoded message
     * @param dIter - The decode iterator
     * @param error - Error information in case of failure
     * @return {@link PerfToolsReturnCodes#SUCCESS} for successful request
     *         processing, < {@link PerfToolsReturnCodes#SUCCESS} when request
     *         processing fails.
     */
    public int processMsg(ChannelHandler channelHandler, ClientChannelInfo clientChannelInfo, Msg msg, DecodeIterator dIter, Error error)
    {
        switch (msg.msgClass())
        {
            case MsgClasses.REQUEST:
                
                // decode login request
                _loginRequest.clear();
                int ret = _loginRequest.decode(dIter, msg);
                if (ret != CodecReturnCodes.SUCCESS)
                {
                    error.text("LoginRequest.decode() failed with return code:  " + CodecReturnCodes.toString(ret));
                    error.errorId(ret);
                    return PerfToolsReturnCodes.FAILURE;
                }

                //send login response
                return sendRefresh(channelHandler, clientChannelInfo, error);
            case MsgClasses.CLOSE:
                System.out.println("Received Login Close for streamId " + msg.streamId());
                break;

            default:
                error.text("Received Unhandled Login Msg Class: " + msg.msgClass());
                error.errorId(PerfToolsReturnCodes.FAILURE);
                return PerfToolsReturnCodes.FAILURE;
        }

        return PerfToolsReturnCodes.SUCCESS;
    }

    /*
     * Encodes and sends login refresh.
     */
    private int sendRefresh(ChannelHandler channelHandler, ClientChannelInfo clientChannelInfo, Error error)
    {
        // initialize login response info
        _loginRefresh.clear();

        Channel channel = clientChannelInfo.channel;
        
        // get a buffer for the login response
        TransportBuffer msgBuf = channel.getBuffer(REFRESH_MSG_SIZE, false, error);

        if (msgBuf == null)
        {
            return PerfToolsReturnCodes.FAILURE;
        }

        // provide login response information 

        // StreamId
        _loginRefresh.streamId(_loginRequest.streamId());

        // Username
        _loginRefresh.applyHasUserName();
        _loginRefresh.userName().data(_loginRequest.userName().data(), _loginRequest.userName().position(), _loginRequest.userName().length());

        _loginRefresh.applyHasUserNameType();
        _loginRefresh.userNameType(Login.UserIdTypes.NAME);

        _loginRefresh.state().code(StateCodes.NONE);
        _loginRefresh.state().dataState(DataStates.OK);
        _loginRefresh.state().streamState(StreamStates.OPEN);
        _loginRefresh.state().text().data("Login accepted by host localhost");

        _loginRefresh.applySolicited();

       
        _loginRefresh.applyHasAttrib();
        
        // ApplicationId
        _loginRefresh.attrib().applyHasApplicationId();
        _loginRefresh.attrib().applicationId().data(_applicationId);

        // ApplicationName
        _loginRefresh.attrib().applyHasApplicationName();
        _loginRefresh.attrib().applicationName().data(_applicationName);

         // Position
        _loginRefresh.attrib().applyHasPosition();
        _loginRefresh.attrib().position().data(_position);

        //
        // this provider does not support
        // SingleOpen behavior
        //
        _loginRefresh.attrib().applyHasSingleOpen();
        _loginRefresh.attrib().singleOpen(0);


        //
        // this provider supports
        // batch requests
        //
        _loginRefresh.applyHasFeatures();
        _loginRefresh.features().applyHasSupportBatchRequests();
        _loginRefresh.features().supportBatchRequests(1); 
        
        _loginRefresh.features().applyHasSupportPost();
        _loginRefresh.features().supportOMMPost(1);


        // keep default values for all others

        // encode login response
        _encodeIter.clear();
        int ret = _encodeIter.setBufferAndRWFVersion(msgBuf, channel.majorVersion(), channel.minorVersion());
        if (ret != CodecReturnCodes.SUCCESS)
        {
            error.text("EncodeIter.setBufferAndRWFVersion() failed with return code: " + CodecReturnCodes.toString(ret));
            error.errorId(ret);
            return PerfToolsReturnCodes.FAILURE;
        }

        ret = _loginRefresh.encode(_encodeIter);
        if (ret != CodecReturnCodes.SUCCESS)
        {
            error.text("LoginRefresh.encode() failed with return code: " + CodecReturnCodes.toString(ret));
            error.errorId(ret);
            return PerfToolsReturnCodes.FAILURE;
        }

        //send login response
        return channelHandler.writeChannel(clientChannelInfo, msgBuf, 0, error);
    }

    /*
     * Encodes and sends login refresh.
     */
    int sendRefreshReactor(ClientChannelInfo clientChannelInfo, Error error)
    {
        // initialize login response info
        _loginRefresh.clear();

        ReactorChannel reactorChannel = clientChannelInfo.reactorChannel;
        
        // get a buffer for the login response
        TransportBuffer msgBuf = reactorChannel.getBuffer(REFRESH_MSG_SIZE, false, _errorInfo);

        if (msgBuf == null)
        {
            return PerfToolsReturnCodes.FAILURE;
        }

        // provide login response information 

        // StreamId
        _loginRefresh.streamId(_loginRequest.streamId());

        // Username
        _loginRefresh.applyHasUserName();
        _loginRefresh.userName().data(_loginRequest.userName().data(), _loginRequest.userName().position(), _loginRequest.userName().length());

        _loginRefresh.applyHasUserNameType();
        _loginRefresh.userNameType(Login.UserIdTypes.NAME);

        _loginRefresh.state().code(StateCodes.NONE);
        _loginRefresh.state().dataState(DataStates.OK);
        _loginRefresh.state().streamState(StreamStates.OPEN);
        _loginRefresh.state().text().data("Login accepted by host localhost");

        _loginRefresh.applySolicited();

       
        _loginRefresh.applyHasAttrib();
        
        // ApplicationId
        _loginRefresh.attrib().applyHasApplicationId();
        _loginRefresh.attrib().applicationId().data(_applicationId);

        // ApplicationName
        _loginRefresh.attrib().applyHasApplicationName();
        _loginRefresh.attrib().applicationName().data(_applicationName);

         // Position
        _loginRefresh.attrib().applyHasPosition();
        _loginRefresh.attrib().position().data(_position);

        //
        // this provider does not support
        // SingleOpen behavior
        //
        _loginRefresh.attrib().applyHasSingleOpen();
        _loginRefresh.attrib().singleOpen(0);


        //
        // this provider supports
        // batch requests
        //
        _loginRefresh.applyHasFeatures();
        _loginRefresh.features().applyHasSupportBatchRequests();
        _loginRefresh.features().supportBatchRequests(1); 
        
        _loginRefresh.features().applyHasSupportPost();
        _loginRefresh.features().supportOMMPost(1);


        // keep default values for all others

        // encode login response
        _encodeIter.clear();
        int ret = _encodeIter.setBufferAndRWFVersion(msgBuf, reactorChannel.majorVersion(), reactorChannel.minorVersion());
        if (ret != CodecReturnCodes.SUCCESS)
        {
            error.text("EncodeIter.setBufferAndRWFVersion() failed with return code: " + CodecReturnCodes.toString(ret));
            error.errorId(ret);
            return PerfToolsReturnCodes.FAILURE;
        }

        ret = _loginRefresh.encode(_encodeIter);
        if (ret != CodecReturnCodes.SUCCESS)
        {
            error.text("LoginRefresh.encode() failed with return code: " + CodecReturnCodes.toString(ret));
            error.errorId(ret);
            return PerfToolsReturnCodes.FAILURE;
        }

        //send login response
        return reactorChannel.submit(msgBuf, _reactorSubmitOptions, _errorInfo);
    }

    /**
     * Returns DACS application id for the login message.
     * 
     * @return  DACS application id for the login message.
     */
    public String applicationId()
    {
        return _applicationId;
    }

    /**
     * Sets DACS application id for the login message.
     * 
     * @param applicationId
     */
    public void applicationId(String applicationId)
    {
        this._applicationId = applicationId;
    }

    /**
     * Returns applicationName for the login message. 
     * 
     * @return applicationName.
     */
    public String applicationName()
    {
        return _applicationName;
    }

    /**
     * Sets applicationName for the login message. 
     * 
     * @param applicationName
     */
    public void applicationName(String applicationName)
    {
        this._applicationName = applicationName;
    }

    /**
     * Returns DACS position for login message.
     * 
     * @return DACS position for login message.
     */
    public String position()
    {
        return _position;
    }

    /**
     * Sets DACS position for login message.
     * 
     * @param position
     */
    public void position(String position)
    {
        this._position = position;
    }

    /**
     * Initializes default DACS position for login message.
     */
    public void initDefaultPosition()
    {
        // Position
        try
        {
            _position = InetAddress.getLocalHost().getHostAddress() + "/"
                    + InetAddress.getLocalHost().getHostName();
        }
        catch (Exception e)
        {
            _position = "1.1.1.1/net";
        }
    }

    /** Returns loginRequest. */
    public LoginRequest loginRequest()
    {
        return _loginRequest;
    }
}