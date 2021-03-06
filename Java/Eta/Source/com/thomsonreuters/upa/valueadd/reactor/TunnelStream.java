package com.thomsonreuters.upa.valueadd.reactor;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.TimeZone;

import com.thomsonreuters.upa.codec.Buffer;
import com.thomsonreuters.upa.codec.CodecFactory;
import com.thomsonreuters.upa.codec.CodecReturnCodes;
import com.thomsonreuters.upa.codec.DataTypes;
import com.thomsonreuters.upa.codec.DecodeIterator;
import com.thomsonreuters.upa.codec.EncodeIterator;
import com.thomsonreuters.upa.codec.GenericMsg;
import com.thomsonreuters.upa.codec.Msg;
import com.thomsonreuters.upa.codec.MsgClasses;
import com.thomsonreuters.upa.codec.RequestMsg;
import com.thomsonreuters.upa.codec.StatusMsg;
import com.thomsonreuters.upa.codec.StreamStates;
import com.thomsonreuters.upa.codec.DataStates;
import com.thomsonreuters.upa.codec.RefreshMsg;
import com.thomsonreuters.upa.codec.StateCodes;
import com.thomsonreuters.upa.rdm.ClassesOfService;
import com.thomsonreuters.upa.rdm.DomainTypes;
import com.thomsonreuters.upa.transport.Channel;
import com.thomsonreuters.upa.transport.ChannelInfo;
import com.thomsonreuters.upa.transport.Error;
import com.thomsonreuters.upa.transport.IoctlCodes;
import com.thomsonreuters.upa.transport.TransportBuffer;
import com.thomsonreuters.upa.transport.TransportFactory;
import com.thomsonreuters.upa.transport.TransportReturnCodes;
import com.thomsonreuters.upa.transport.WriteArgs;
import com.thomsonreuters.upa.valueadd.common.VaDoubleLinkList;
import com.thomsonreuters.upa.valueadd.common.VaDoubleLinkList.Link;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.MsgBase;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginMsg;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginMsgFactory;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginMsgType;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginRequest;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueAck;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueData;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueDataExpired;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueDataTimeoutCode;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueDataUndeliverableCode;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueMsg;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueMsgType;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueRequest;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.queue.QueueMsgFactory;
import com.thomsonreuters.upa.valueadd.reactor.ReactorChannel.State;
import com.thomsonreuters.upa.valueadd.reactor.TunnelStreamMsg.OpCodes;
import com.thomsonreuters.upa.valueadd.reactor.TunnelStreamStateInfo.TunnelStreamState;
import com.thomsonreuters.upa.valueadd.reactor.TunnelSubstream.TunnelSubstreamState;

/**
 * Class to operate upon a TunnelStream. A tunnel stream is a private stream that supports
 * interactions with guaranteed messaging and other auxiliary services.
 *
 */
public class TunnelStream
{
    ReactorChannel _reactorChannel;
    Reactor _reactor;
    QueueData _queueData;
    QueueDataExpired _queueDataExpired;
    QueueAck _queueAck;
    ReactorErrorInfo _errorInfo;
    int _streamId;
    int _channelStreamId;
    int _domainType;
    int _serviceId;
    // temporary GenericMsg and DecodeIterator for QueueData submit
    GenericMsg _genericMsg;
    DecodeIterator _dIter;
    EncodeIterator _eIter;
	com.thomsonreuters.upa.codec.State _state;
    TunnelStreamDefaultMsgCallback _defaultMsgCallback;
    TunnelStreamStatusEventCallback _statusEventCallback;
    TunnelStreamQueueMsgCallback _queueMsgCallback;
    LoginRequest _authLoginRequest;
    String _name; // TunnelStream name
    int _guaranteedOutputBuffers;
	Object _userSpecObject;
	boolean _isProvider;
    WlInteger _tempWlInteger = ReactorFactory.createWlInteger();
    WlInteger _tableKey;
    ReactorSubmitOptions _reactorSubmitOptions = ReactorFactory.createReactorSubmitOptions();

    static final int DEFAULT_RECV_WINDOW = CosCommon.DEFAULT_MAX_MSG_SIZE * 2;

    static final int CONTAINER_TYPE_POSITION = 9; 

    /* Reusable memory for stream-level retransmission. */
    SlicedBufferPool                        _bufferPool;

	/* Link for TunnelManager overall list. */
	private TunnelStream _managerNext, _managerPrev;
	static class ManagerLink implements Link<TunnelStream>
	{
		public TunnelStream getPrev(TunnelStream thisPrev) { return thisPrev._managerPrev; }
		public void setPrev(TunnelStream thisPrev, TunnelStream thatPrev) { thisPrev._managerPrev = thatPrev; }
		public TunnelStream getNext(TunnelStream thisNext) { return thisNext._managerNext; }
		public void setNext(TunnelStream thisNext, TunnelStream thatNext) { thisNext._managerNext = thatNext; }
	}
	static final ManagerLink MANAGER_LINK = new ManagerLink();

	/* Link for list of queues that need dispatching. */
    private TunnelStream _dispatchNext, _dispatchPrev;
	static class DispatchLink implements Link<TunnelStream>
	{
		public TunnelStream getPrev(TunnelStream thisPrev) { return thisPrev._dispatchPrev; }
		public void setPrev(TunnelStream thisPrev, TunnelStream thatPrev) { thisPrev._dispatchPrev = thatPrev; }
		public TunnelStream getNext(TunnelStream thisNext) { return thisNext._dispatchNext; }
		public void setNext(TunnelStream thisNext, TunnelStream thatNext) { thisNext._dispatchNext = thatNext; }
	}
	static final DispatchLink DISPATCH_LINK = new DispatchLink();

	/* Link for queues with timer events. */
	private TunnelStream _timeoutNext, _timeoutPrev;
	static class TimeoutLink implements Link<TunnelStream>
	{
		public TunnelStream getPrev(TunnelStream thisPrev) { return thisPrev._timeoutPrev; }
		public void setPrev(TunnelStream thisPrev, TunnelStream thatPrev) { thisPrev._timeoutPrev = thatPrev; }
		public TunnelStream getNext(TunnelStream thisNext) { return thisNext._timeoutNext; }
		public void setNext(TunnelStream thisNext, TunnelStream thatNext) { thisNext._timeoutNext = thatNext; }
	}
	static final TimeoutLink TIMEOUT_LINK = new TimeoutLink();

    VaDoubleLinkList<TunnelStreamBuffer>  _tunnelStreamBufferPool;  /* (RETRANS_LINK) Reusable pool of tunnel stream buffer objects. */
	VaDoubleLinkList<TunnelStreamBuffer>	_outboundMsgAckList;	  /* (RETRANS_LINK) Messages that have been written to the channel and are awaiting acknowledgement. */
	VaDoubleLinkList<TunnelStreamBuffer>	_outboundTransmitList;	  /* (RETRANS_LINK) Messages that require retransmission. */
    VaDoubleLinkList<TunnelStreamBuffer>   _outboundTimeoutList;    /* (TIMEOUT_LINK) Messages that have a timeout value and may expire. */
    VaDoubleLinkList<TunnelStreamBuffer>   _outboundImmediateList;  /* (TIMEOUT_LINK) Messages that will expire immediately if stream is not open. */
	
	WriteArgs _writeArgs;
	TunnelStreamMsg _tunnelStreamMsg;
    GenericMsg _tunnelStreamHdr;
    Buffer _tmpBuffer; /* Used for encoding extended header of TunnelStream header. */
	AckRangeList	_sendNakRangeList;
	AckRangeList	_recvNakRangeList;
	AckRangeList	_recvAckRangeList;
	
	/* Counts the number of queued/unacked outbound application buffers
	 * (the outbound lists contain both application-encoded buffers and
	 * acknowledgements -- want a count of application buffers only). */
	int _outboundQueuedMsgCount;
	int _outboundUnackedAppMsgCount;

	boolean _notifying;
	TunnelStreamStateInfo.TunnelStreamState _tunnelStreamState;

	int _traceFlags = TunnelStreamTraceFlags.NONE;
	AckRangeList _traceAckRangeList, _traceNakRangeList;
	Msg _traceMsg, _traceSubMsg;
	DecodeIterator _traceIter;
	SimpleDateFormat _traceDateFormat;
	static final TimeZone _traceTimeZone = TimeZone.getTimeZone("UTC");
    Msg _xmlMsg = CodecFactory.createMsg();
    DecodeIterator _xmlIter = CodecFactory.createDecodeIterator();
    ChannelInfo _chnlInfo = TransportFactory.createChannelInfo();

	/* Tunnel stream/channel attributes, when stream is open. */
	ClassOfService _classOfService = new ClassOfService();

	/* Reusable members for writing/reading on open streams. */
	EncodeIterator _encIter;
	Msg _encMsg;
    Msg _decMsg;
    Msg _decSubMsg;
	DecodeIterator _decIter, _decSubIter;
	
	QueueMsgImpl _queueSubstreamHeader;	
	QueueRequestImpl _queueRequest;
	QueueRefreshImpl _queueRefresh;	
	QueueCloseImpl _queueClose;
	QueueStatusImpl _queueStatus;
    LoginMsg _loginMsg;
	
	long _nextTimeoutNsec; // next timeout from all substreams
	int _responseTimeout;
	boolean _hasNextTimeout;
	boolean _streamOpen;
	
	
	int _sendBytes;
	int _sendLastSeqNum;
	int _sendLastSeqNumAcked;
    int _sendLastSeqNumNaked;
	int _recvBytes;
	int _recvLastSeqNum;
	int _recvLastSeqNumAckSent;
	boolean _firstIsSendWindowOpenCall;
	
	boolean _providerLoginRefreshSent;
		
	HashMap<Integer,TunnelSubstream> _streamIdtoQueueSubstreamTable;
	
	TransportBuffer _writeCallAgainBuffer;
	
	long finAckTimeout = 150; // ms
	int _max_num_timeout_retry = 3;
	int _sendFinSeqNum;	
	boolean _hasFinSent = false;
	boolean _initialFinSent = false;
	int _finAckWaitCount = 0;
	int _ackOpcodeFin = 0x1;
	int _ackOpcodeFinAck = 0x1;
	boolean _receivedFinAck = false;
	boolean _receivedFinalFin = false;
	int _receivedFinalFinSeqNum;
	int _receivedLastFinSeqNum;
	boolean _finalStatusEvent; 
	// for Junit testing
    boolean _forceFileReset;
		
	// set to true for QueueMsg tracing
	boolean _enableQueueMsgTracing = false;
    
    /** Creates a consumer-side tunnel stream. */
    public TunnelStream(ReactorChannel reactorChannel, TunnelStreamOpenOptions options)
    {
        this(reactorChannel);
        _streamId = options.streamId();
        _domainType = options.domainType();
        _serviceId = options.serviceId();
        _guaranteedOutputBuffers = options.guaranteedOutputBuffers();
        options.classOfService().copy(_classOfService);
        _defaultMsgCallback = options.defaultMsgCallback();
        _statusEventCallback = options.statusEventCallback();
        _queueMsgCallback = options.queueMsgCallback();
        _authLoginRequest = options.authLoginRequest();
        if (_authLoginRequest == null && reactorChannel.role() != null)
        {
            _authLoginRequest = ((ConsumerRole)reactorChannel.role()).rdmLoginRequest();
        }
        _name = options.name();
        _userSpecObject = options.userSpecObject();
        _responseTimeout = options.responseTimeout();
    }
    
    /** Creates a provider-side tunnel stream. */
    public TunnelStream(ReactorChannel reactorChannel, TunnelStreamRequestEvent event, TunnelStreamAcceptOptions options)
    {
        this(reactorChannel);
        _streamId = event.streamId();
        _domainType = event.domainType();
        _serviceId = event.serviceId();
        _guaranteedOutputBuffers = options.guaranteedOutputBuffers();
        options.classOfService().copy(_classOfService);
        _defaultMsgCallback = options.defaultMsgCallback();
        _statusEventCallback = options.statusEventCallback();
        _name = event.name();
        _userSpecObject = options.userSpecObject();
        _isProvider = true;
    }

    TunnelStream(ReactorChannel reactorChannel)
    {
        _reactorChannel = reactorChannel;
        _reactor = _reactorChannel.reactor();
        _queueData = QueueMsgFactory.createQueueData();
        _queueDataExpired = QueueMsgFactory.createQueueDataExpired();
        _queueAck = QueueMsgFactory.createQueueAck();
        _errorInfo = ReactorFactory.createReactorErrorInfo();
        _genericMsg = (GenericMsg)CodecFactory.createMsg();
        _dIter = CodecFactory.createDecodeIterator();
        _eIter = CodecFactory.createEncodeIterator();
        _state = CodecFactory.createState();
        _state.streamState(StreamStates.OPEN);
        _state.dataState(DataStates.SUSPECT);
        _state.code(StateCodes.NONE);

        _tunnelStreamBufferPool = new VaDoubleLinkList<TunnelStreamBuffer>();
		_outboundMsgAckList = new VaDoubleLinkList<TunnelStreamBuffer>();
		_outboundTransmitList = new VaDoubleLinkList<TunnelStreamBuffer>();
        _outboundImmediateList = new VaDoubleLinkList<TunnelStreamBuffer>();
        _outboundTimeoutList = new VaDoubleLinkList<TunnelStreamBuffer>();

		_tunnelStreamMsg = new TunnelStreamMsgImpl();
		 
		_queueRequest = (QueueRequestImpl)QueueMsgFactory.createQueueRequest();
		_queueRefresh = (QueueRefreshImpl)QueueMsgFactory.createQueueRefresh();
		_queueData = (QueueDataImpl)QueueMsgFactory.createQueueData();
		 		 
		_queueDataExpired = (QueueDataExpiredImpl)QueueMsgFactory.createQueueDataExpired();
		_queueAck = (QueueAckImpl)QueueMsgFactory.createQueueAck();
		_queueClose = (QueueCloseImpl)QueueMsgFactory.createQueueClose();
		_queueStatus = (QueueStatusImpl)QueueMsgFactory.createQueueStatus(); 
		 	        
		_tunnelStreamHdr = (GenericMsg)CodecFactory.createMsg();
		_tmpBuffer = CodecFactory.createBuffer();
		_sendNakRangeList = new AckRangeList();
		_recvAckRangeList = new AckRangeList();
		_recvNakRangeList = new AckRangeList();
		
		
		_writeArgs = TransportFactory.createWriteArgs();
		_encIter = CodecFactory.createEncodeIterator();
		_decIter = CodecFactory.createDecodeIterator();
		_decSubIter = CodecFactory.createDecodeIterator();
		_encMsg = CodecFactory.createMsg();
		_decMsg = CodecFactory.createMsg();
		_decSubMsg = CodecFactory.createMsg();
		_loginMsg = LoginMsgFactory.createMsg();
		
		_tunnelStreamState = TunnelStreamState.NOT_OPEN;

		_traceIter = CodecFactory.createDecodeIterator();
		_traceMsg = CodecFactory.createMsg();
		_traceSubMsg = CodecFactory.createMsg();
		_traceAckRangeList = new AckRangeList();
		_traceNakRangeList = new AckRangeList();
		_traceDateFormat = new SimpleDateFormat("dd MMM yyyy HH:mm:ss.SSS");
		_traceDateFormat.setTimeZone(_traceTimeZone);
		
		_streamIdtoQueueSubstreamTable = new HashMap<Integer,TunnelSubstream>();
		
		_receivedFinAck = false;
		_receivedFinalFin = false;	
		_hasFinSent = false;
		_receivedLastFinSeqNum = -1;
		_finalStatusEvent = true;
	}
    
    /**
     * Gets a buffer from the TunnelStream for writing a message.
     * 
     * @param size the size(in bytes) of the buffer to get
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return the buffer for writing the message or
     *         null, if an error occurred (errorInfo will be populated with information)
     */
    public TransportBuffer getBuffer(int size, ReactorErrorInfo errorInfo)
    {
        _reactor._reactorLock.lock();
        
        try
        {
            TunnelStreamBuffer buffer = null;

            buffer = getBuffer(size, true, true, errorInfo.error());

            if (buffer != null)
            {
                errorInfo.error().text("");
                errorInfo.error().errorId(ReactorReturnCodes.SUCCESS);
            }
            else // no buffers
            {
                _reactor.populateErrorInfo(errorInfo, 
                        errorInfo.error().errorId(),
                        "TunnelStream.getBuffer",
                        errorInfo.error().text());
            }

            return buffer;
        }
        finally
        {
            _reactor._reactorLock.unlock();          
        }        
    }
    
    /**
     * Returns an unwritten buffer to the TunnelStream.
     *
     * @param buffer the buffer to release
     * @param errorInfo error structure to be populated in the event of failure
     *
     * @return {@link ReactorReturnCodes} indicating success or failure
     */
    public int releaseBuffer(TransportBuffer buffer, ReactorErrorInfo errorInfo)
    {
        _reactor._reactorLock.lock();
        
        try
        {
            return releaseBuffer((TunnelStreamBuffer)buffer, errorInfo.error());
        }
        finally
        {
            _reactor._reactorLock.unlock();          
        }
    }
    
    /**
     * Sends a buffer to the tunnel stream.
     * 
     * @param buffer the buffer to send
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return {@link ReactorReturnCodes#SUCCESS}, if submit succeeded or
     * {@link ReactorReturnCodes#PERSISTENCE_FULL}, if the persistence file is full or
     * {@link ReactorReturnCodes#INVALID_ENCODING}, if the buffer encoding is invalid or
     * {@link ReactorReturnCodes#FAILURE}, if submit failed (refer to errorInfo for additional information)
     */
    public int submit(TransportBuffer buffer, TunnelStreamSubmitOptions options, ReactorErrorInfo errorInfo)
    {
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (buffer == null)
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "TunnelStream.submit",
                                               "buffer cannot be null.");
        else if (options == null)
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "TunnelStream.submit",
                                               "options cannot be null.");
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "TunnelStream.submit",
                                              "Reactor is shutdown, submit aborted.");

        return handleBufferSubmit(_reactorChannel, buffer, options.containerType(), errorInfo);     
    }

    /**
     * Sends an RDM message to the tunnel stream.
     * 
     * @param rdmMsg the RDM message to send
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return {@link ReactorReturnCodes#SUCCESS}, if submit succeeded or
     * {@link ReactorReturnCodes#PERSISTENCE_FULL}, if the persistence file is full or
     * {@link ReactorReturnCodes#FAILURE}, if submit failed (refer to errorInfo for additional information)
     */
    public int submit(MsgBase rdmMsg, ReactorErrorInfo errorInfo)
    {    
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (rdmMsg == null)
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "TunnelStream.submit",
                                               "rdmMsg cannot be null.");
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "TunnelStream.submit",
                                              "Reactor is shutdown, submit aborted.");

        if (_classOfService.guarantee().type() != ClassesOfService.GuaranteeTypes.PERSISTENT_QUEUE ||
            rdmMsg.domainType() == DomainTypes.LOGIN ||
            rdmMsg.domainType() == DomainTypes.SOURCE ||
            rdmMsg.domainType() == DomainTypes.DICTIONARY ||
            rdmMsg.domainType() == DomainTypes.SYMBOL_LIST)
        {
            return handleRDMSubmit(_reactorChannel, rdmMsg, errorInfo);
        }
        else
        {
            return handleQueueMsgRDMSubmit(_reactorChannel, (QueueMsg)rdmMsg, errorInfo);
        }
    }
    
    private int handleRDMSubmit(ReactorChannel reactorChannel, MsgBase rdmMsg, ReactorErrorInfo errorInfo)
    {
        int ret = ReactorReturnCodes.SUCCESS;
        
        _reactor._reactorLock.lock();
        
        try
        {
            if (reactorChannel.state() == ReactorChannel.State.CLOSED)
            {
                ret = ReactorReturnCodes.FAILURE;
                return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                         "TunnelStream.submit", "ReactorChannel is closed, aborting.");
            }
            
            int bufLength = _reactor.getMaxFragmentSize(reactorChannel, errorInfo);
            TransportBuffer buffer = getBuffer(bufLength, errorInfo);
            
            if (buffer != null)
            {
                _eIter.clear();
                _eIter.setBufferAndRWFVersion(buffer, _reactorChannel.majorVersion(), _reactorChannel.minorVersion());
                if ((ret = rdmMsg.encode(_eIter)) != CodecReturnCodes.SUCCESS)
                {
                    releaseBuffer(buffer, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                                      ret,
                                                      "TunnelStream.submit",
                                                      "Unable to encode RDM Msg");
                }
                
                if ((ret = handleBufferSubmit(_reactorChannel,
                                              (TunnelStreamBuffer)buffer,
                                              DataTypes.MSG,
                                              errorInfo)) < ReactorReturnCodes.SUCCESS)
                {
                    releaseBuffer(buffer, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                                      ret,
                                                      "TunnelStream.submit",
                                                      "TunnelStream.submit() failed");
                }
            }
            else // cannot get buffer
            {
                // send FLUSH event to worker
                if (!_reactor.sendWorkerEvent(WorkerEventTypes.FLUSH, reactorChannel))
                {
                    // _reactor.sendWorkerEvent() failed, send channel down
                    _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, reactorChannel);
                    reactorChannel.state(State.DOWN);
                    _reactor.sendAndHandleChannelEventCallback("TunnelStream.submit",
                                                          ReactorChannelEventTypes.CHANNEL_DOWN,
                                                          reactorChannel, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                      ReactorReturnCodes.FAILURE,
                                      "TunnelStream.submit",
                                      "_reactor.sendWorkerEvent() failed");
                }
                
                _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.NO_BUFFERS,
                        "TunnelStream.submit", "channel out of buffers chnl="
                                + reactorChannel.channel().selectableChannel() + " errorId="
                                + errorInfo.error().errorId() + " errorText="
                                + errorInfo.error().text());
                
                return ReactorReturnCodes.NO_BUFFERS;
            }
        }
        finally
        {
            _reactor._reactorLock.unlock();          
        }
        
        return ReactorReturnCodes.SUCCESS;
    }
    
    private int handleQueueMsgRDMSubmit(ReactorChannel reactorChannel, QueueMsg queueMsg, ReactorErrorInfo errorInfo)
    {
        int ret = ReactorReturnCodes.SUCCESS;
        
        _reactor._reactorLock.lock();
        
        try
        {
            if (reactorChannel.state() == ReactorChannel.State.CLOSED)
            {
                ret = ReactorReturnCodes.FAILURE;
                return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                         "TunnelStream.submit", "ReactorChannel is closed, aborting.");
            }
            
            switch (queueMsg.rdmMsgType())
            {
                case REQUEST:
                {
                    return openQueueMsgStream(reactorChannel, (QueueRequest)queueMsg, _serviceId, errorInfo);
                }
                case CLOSE:
                case REFRESH:
                case STATUS:
                case ACK:
                case DATA:
                {
                    int bufLength = (queueMsg.rdmMsgType() == QueueMsgType.DATA ? ((QueueData)queueMsg).encodedDataBody().length() + queueDataHdrBufSize((QueueData)queueMsg) : 128);
                    if (bufLength > _classOfService.common().maxMsgSize())
                    {
                        bufLength = _classOfService.common().maxMsgSize();
                    }
                    TransportBuffer buffer = getBuffer(bufLength, errorInfo);
                    
                    if (buffer != null)
                    {
                        _eIter.clear();
                        _eIter.setBufferAndRWFVersion(buffer, _reactorChannel.majorVersion(), _reactorChannel.minorVersion());
                        if ((ret = queueMsg.encode(_eIter)) != CodecReturnCodes.SUCCESS)
                        {
                            releaseBuffer(buffer, errorInfo);
                            return _reactor.populateErrorInfo(errorInfo,
                                                              ret,
                                                              "TunnelStream.submit",
                                                              "Unable to encode QueueMsg");
                        }
                        
                        if ((ret = handleBufferSubmit(_reactorChannel,
                                                      (TunnelStreamBuffer)buffer,
                                                      DataTypes.MSG,
                                                      errorInfo)) < ReactorReturnCodes.SUCCESS)
                        {
                            releaseBuffer(buffer, errorInfo);
                            return _reactor.populateErrorInfo(errorInfo,
                                                              ret,
                                                              "TunnelStream.submit",
                                                              "TunnelStream.submit() failed");
                        }
                    }
                    else // cannot get buffer
                    {
                        // send FLUSH event to worker
                        if (!_reactor.sendWorkerEvent(WorkerEventTypes.FLUSH, reactorChannel))
                        {
                            // _reactor.sendWorkerEvent() failed, send channel down
                            _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, reactorChannel);
                            reactorChannel.state(State.DOWN);
                            _reactor.sendAndHandleChannelEventCallback("TunnelStream.submit",
                                                                  ReactorChannelEventTypes.CHANNEL_DOWN,
                                                                  reactorChannel, errorInfo);
                            return _reactor.populateErrorInfo(errorInfo,
                                              ReactorReturnCodes.FAILURE,
                                              "TunnelStream.submit",
                                              "_reactor.sendWorkerEvent() failed");
                        }
                        
                        _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.NO_BUFFERS,
                                "TunnelStream.submit", "channel out of buffers chnl="
                                        + reactorChannel.channel().selectableChannel() + " errorId="
                                        + errorInfo.error().errorId() + " errorText="
                                        + errorInfo.error().text());
                        
                        return ReactorReturnCodes.NO_BUFFERS;
                    }
                    break;
                }
                default:
                {
                    return _reactor.populateErrorInfo(errorInfo,
                                                      ReactorReturnCodes.FAILURE,
                                                      "TunnelStream.submit",
                                                      "Unsupported QueueMsgType");                                    
                }
            }
        }
        finally
        {
            _reactor._reactorLock.unlock();          
        }
        
        return ReactorReturnCodes.SUCCESS;
    }
    
    int queueDataHdrBufSize(QueueData queueMsg)
    {
        return 128 + queueMsg.sourceName().length() + queueMsg.destName().length();
    }

    /**
     * Sends a message to the tunnel stream.
     * 
     * @param msg the message to send
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return {@link ReactorReturnCodes#SUCCESS}, if submit succeeded or
     * {@link ReactorReturnCodes#FAILURE}, if submit failed (refer to errorInfo for additional information)
     */
    public int submit(Msg msg, ReactorErrorInfo errorInfo)
    {    
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (msg == null)
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "TunnelStream.submit",
                                               "msg cannot be null.");
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "TunnelStream.submit",
                                              "Reactor is shutdown, submit aborted.");
        
        return handleMsgSubmit(_reactorChannel, msg, errorInfo);
    }

    private int handleMsgSubmit(ReactorChannel reactorChannel, Msg msg, ReactorErrorInfo errorInfo)
    {
        int ret = ReactorReturnCodes.SUCCESS;
        
        _reactor._reactorLock.lock();
        
        try
        {
            if (reactorChannel.state() == ReactorChannel.State.CLOSED)
            {
                ret = ReactorReturnCodes.FAILURE;
                return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                         "TunnelStream.submit", "ReactorChannel is closed, aborting.");
            }
            
            int bufLength = _reactor.getMaxFragmentSize(reactorChannel, errorInfo);
            TransportBuffer buffer = getBuffer(bufLength, errorInfo);
            
            if (buffer != null)
            {
                _eIter.clear();
                _eIter.setBufferAndRWFVersion(buffer, _reactorChannel.majorVersion(), _reactorChannel.minorVersion());
                if ((ret = msg.encode(_eIter)) != CodecReturnCodes.SUCCESS)
                {
                    releaseBuffer(buffer, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                                      ret,
                                                      "TunnelStream.submit",
                                                      "Unable to encode Msg");
                }
                
                if ((ret = handleBufferSubmit(_reactorChannel,
                                              (TunnelStreamBuffer)buffer,
                                              DataTypes.MSG,
                                              errorInfo)) < ReactorReturnCodes.SUCCESS)
                {
                    releaseBuffer(buffer, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                                      ret,
                                                      "TunnelStream.submit",
                                                      "TunnelStream.submit() failed");
                }
            }
            else // cannot get buffer
            {
                // send FLUSH event to worker
                if (!_reactor.sendWorkerEvent(WorkerEventTypes.FLUSH, reactorChannel))
                {
                    // _reactor.sendWorkerEvent() failed, send channel down
                    _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, reactorChannel);
                    reactorChannel.state(State.DOWN);
                    _reactor.sendAndHandleChannelEventCallback("TunnelStream.submit",
                                                          ReactorChannelEventTypes.CHANNEL_DOWN,
                                                          reactorChannel, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                      ReactorReturnCodes.FAILURE,
                                      "TunnelStream.submit",
                                      "_reactor.sendWorkerEvent() failed");
                }
                
                _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.NO_BUFFERS,
                        "TunnelStream.submit", "channel out of buffers chnl="
                                + reactorChannel.channel().selectableChannel() + " errorId="
                                + errorInfo.error().errorId() + " errorText="
                                + errorInfo.error().text());
                
                return ReactorReturnCodes.NO_BUFFERS;
            }
        }
        finally
        {
            _reactor._reactorLock.unlock();          
        }
        
        return ReactorReturnCodes.SUCCESS;
    }

    private int handleBufferSubmit(ReactorChannel reactorChannel, TransportBuffer buffer, int containerType, ReactorErrorInfo errorInfo)
    {
        int ret = ReactorReturnCodes.SUCCESS;
        
        _reactor._reactorLock.lock();
        
        try
        {
            if (reactorChannel.state() == ReactorChannel.State.CLOSED)
            {
                ret = ReactorReturnCodes.FAILURE;
                return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                         "TunnelStream.submit", "ReactorChannel is closed, aborting.");
            }
            
            // directly submit encoded buffer to TunnelStream
            if ((ret = submit((TunnelStreamBuffer)buffer,
                                                   containerType,
                                                   errorInfo.error())) < ReactorReturnCodes.SUCCESS)
                return ret;
            
            // send a WorkerEvent to the Worker to immediately expire a timer
            if (reactorChannel.tunnelStreamManager().needsDispatchNow())
            {
                if (!_reactor.sendDispatchNowEvent(reactorChannel))
                {
                    // _reactor.sendDispatchNowEvent() failed, send channel down
                    _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, reactorChannel);
                    reactorChannel.state(State.DOWN);
                    _reactor.sendAndHandleChannelEventCallback("TunnelStream.submit",
                                                          ReactorChannelEventTypes.CHANNEL_DOWN,
                                                          reactorChannel, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                      ReactorReturnCodes.FAILURE,
                                      "TunnelStream.submit",
                                      "_reactor.sendDispatchNowEvent() failed");
                }
            }
            if (reactorChannel.tunnelStreamManager().hasNextDispatchTime())
            {
                if (!_reactor.sendWorkerEvent(WorkerEventTypes.START_DISPATCH_TIMER, reactorChannel, reactorChannel.tunnelStreamManager().nextDispatchTime()))
                {
                    // _reactor.sendWorkerEvent() failed, send channel down
                    _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, reactorChannel);
                    reactorChannel.state(State.DOWN);
                    _reactor.sendAndHandleChannelEventCallback("TunnelStream.dispatchChannel",
                                                          ReactorChannelEventTypes.CHANNEL_DOWN,
                                                          reactorChannel, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                      ReactorReturnCodes.FAILURE,
                                      "TunnelStream.submit",
                                      "_reactor.sendWorkerEvent() failed");
                }
            }
        }
        finally
        {
            _reactor._reactorLock.unlock();          
        }        
        
        return ReactorReturnCodes.SUCCESS;            
    }

    /**
     * Closes the tunnel stream.
     * 
     * The finalStatusEvent argument indicates that the application wishes to receive a final
     * {@link TunnelStreamStatusEvent} when the closing of the tunnel stream to the remote end
     * is complete. If this is set to true, the tunnel stream will be cleaned up once the final
     * {@link TunnelStreamStatusEvent} event is provided to the application. This is indicated
     * by a non-open state.streamState on the event such as {@link StreamStates#CLOSED}
     * or {@link StreamStates#CLOSED_RECOVER}.
     * 
     * @param finalStatusEvent if true, a final {@link TunnelStreamStatusEvent} is provided to the
     *        application when the closing of the tunnel stream to the remote end is complete
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return {@link ReactorReturnCodes} indicating success or failure
     */
    public int close(boolean finalStatusEvent, ReactorErrorInfo errorInfo)
    {   	
        _state.streamState(StreamStates.OPEN);
        _state.dataState(DataStates.SUSPECT);
        _state.code(StateCodes.NONE);
        
        // close TunnelStream for this streamId
        close(finalStatusEvent, errorInfo.error());

        // find and remove TunnelStream from table for this streamId
        _tempWlInteger.value(_streamId);
        if (_reactorChannel.streamIdtoTunnelStreamTable().containsKey(_tempWlInteger))
        { 
            if (_reactorChannel.tunnelStreamManager().needsDispatchNow())
            {
                if (!_reactor.sendDispatchNowEvent(_reactorChannel))
                {
                    // _reactor.sendDispatchNowEvent() failed, send channel down
                    _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, _reactorChannel);
                    _reactorChannel.state(State.DOWN);
                    return ReactorReturnCodes.SUCCESS;
                }
            }
        }
        
        return ReactorReturnCodes.SUCCESS;
    }

    int openQueueMsgStream(ReactorChannel reactorChannel, QueueRequest queueRequest, int serviceId, ReactorErrorInfo errorInfo)
    {
        int retval = ReactorReturnCodes.SUCCESS;

        if ((retval = openSubstream(queueRequest, errorInfo.error())) < ReactorReturnCodes.SUCCESS)
        {
            return _reactor.populateErrorInfo(errorInfo,
                                              retval,
                                              "TunnelStream.openQueueMsgStream",
                                              "TunnelStream.openSubstream() failed <" + errorInfo.error().text() + ">");
        }
        if (_enableQueueMsgTracing)
        {
            enableTrace(TunnelStreamTraceFlags.ACTIONS
                                 | TunnelStreamTraceFlags.MSGS
                                 | TunnelStreamTraceFlags.MSG_XML);
        }
        // send a WorkerEvent to the Worker to immediately expire a timer
        if (reactorChannel.tunnelStreamManager().needsDispatchNow())
        {
            if (!_reactor.sendDispatchNowEvent(reactorChannel))
            {
                // _reactor.sendDispatchNowEvent() failed, send channel down
                _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, reactorChannel);
                reactorChannel.state(State.DOWN);
                _reactor.sendAndHandleChannelEventCallback("TunnelStream.openQueueMsgStream",
                                                      ReactorChannelEventTypes.CHANNEL_DOWN,
                                                      reactorChannel, errorInfo);
                return _reactor.populateErrorInfo(errorInfo,
                                  ReactorReturnCodes.FAILURE,
                                  "TunnelStream.openQueueMsgStream",
                                  "_reactor.sendDispatchNowEvent() failed");
            }
        }
        
        return ReactorReturnCodes.SUCCESS;
    }

    int queueMsgReceived(QueueMsg queueMsg, Msg msg)
    {
        int retval = ReactorCallbackReturnCodes.SUCCESS;
        
        retval = _reactor.sendAndHandleQueueMsgCallback("TunnelStream.queueMsgReceived", _reactorChannel, this, null, msg, queueMsg, _errorInfo);

        if (retval == ReactorCallbackReturnCodes.RAISE)
            retval = _reactor.sendAndHandleTunnelStreamMsgCallback("TunnelStream.queueMsgReceived", _reactorChannel, this, null, msg, DataTypes.MSG, _errorInfo);
        
        return retval;
    }
    
    int queueMsgAcknowledged(QueueAck queueAck, Msg msg)
    {
        int retval = ReactorCallbackReturnCodes.SUCCESS;

        retval = _reactor.sendAndHandleQueueMsgCallback("TunnelStream.queueMsgAcknowledged", _reactorChannel, this, null, msg, queueAck, _errorInfo);

        if (retval == ReactorCallbackReturnCodes.RAISE)
            retval = _reactor.sendAndHandleTunnelStreamMsgCallback("TunnelStream.queueMsgAcknowledged", _reactorChannel, this, null, msg, DataTypes.MSG, _errorInfo);
        
        return retval;
    }

    int queueMsgExpired(TunnelStreamBuffer buffer, Msg msg, int code)
    {
        int retval = ReactorCallbackReturnCodes.SUCCESS;

        /* Move to start of queue message. */
        buffer.setAsInnerReadBuffer();
        
        /* Decode as QueueData message. */
        _decSubIter.clear();
        _decSubIter.setBufferAndRWFVersion(buffer, _reactorChannel.majorVersion(), _reactorChannel.minorVersion());
        _decSubMsg.decode(_decSubIter);
        _queueData.clear();
        _queueData.decode(_decSubIter, _decSubMsg);

        /* Translate to QueueDataExpired message (source & destination should be reversed). */
        _queueDataExpired.clear();
        _queueDataExpired.streamId(_queueData.streamId());
        _queueDataExpired.identifier(_queueData.identifier());
        _queueDataExpired.serviceId(_queueData.serviceId());
        _queueDataExpired.sourceName().data(_queueData.destName().data(), _queueData.destName().position(), _queueData.destName().length());
        _queueDataExpired.destName().data(_queueData.sourceName().data(), _queueData.sourceName().position(), _queueData.sourceName().length());
        _queueDataExpired.undeliverableCode(code);
        _queueDataExpired.encodedDataBody(_queueData.encodedDataBody());
        _queueDataExpired.domainType(_queueData.domainType());
        _queueDataExpired.containerType(_queueData.containerType());

        retval = _reactor.sendAndHandleQueueMsgCallback("TunnelStream.queueMsgExpired", _reactorChannel, this, buffer, msg, _queueDataExpired, _errorInfo);

        if (retval == ReactorCallbackReturnCodes.RAISE)
            retval = _reactor.sendAndHandleTunnelStreamMsgCallback("TunnelStream.queueMsgExpired", _reactorChannel, this, buffer, msg, DataTypes.MSG, _errorInfo);
        
        return retval;
    }
    
    int msgReceived(TunnelStreamBuffer buffer, Msg msg, int containerType)
    {
        return _reactor.sendAndHandleTunnelStreamMsgCallback("TunnelStream.msgReceived", _reactorChannel, this, buffer, msg, containerType, _errorInfo);
    }

    /**
     * The stream id of the tunnel stream.
     * 
     * @return the stream id
     */
    public int streamId()
    {
        return _streamId;
    }
    
    /**
     * The domain type of the tunnel stream.
     * 
     * @return the domain type
     */
    public int domainType()
    {
        return _domainType;
    }
    
    /**
     * The service identifier of the tunnel stream.
     * 
     * @return the service id
     */
    public int serviceId()
    {
        return _serviceId;
    }

    /**
     * The class of service of the tunnel stream.
     * 
     * @return the ClassOfService
     * 
     * @see ClassOfService
     */
    public ClassOfService classOfService()
    {
        return _classOfService;
    }
    
    /**
     * The number of guaranteed output buffers that will be available
     * for the tunnel stream.
     * 
     * @return the number of guaranteed output buffers
     */
    public int guaranteedOutputBuffers()
    {
        return _guaranteedOutputBuffers;
    }

    /**
     * The TunnelStreamStatusEventCallback of the TunnelStream. Handles stream events
     * for tunnel stream.
     * 
     * @return the tunnelStreamDefaultMsgCallback
     */
    public TunnelStreamStatusEventCallback statusEventCallback()
    {
        return _statusEventCallback;
    }

    /**
     * The TunnelStreamDefaultMsgCallback of the TunnelStream. Handles message events
     * for tunnel stream.
     * 
     * @return the tunnelStreamDefaultMsgCallback
     */
    public TunnelStreamDefaultMsgCallback defaultMsgCallback()
    {
        return _defaultMsgCallback;
    }
    
    /**
     * The QueueMsgCallback of the TunnelStream. Handles message events
     * for queue message streams.
     * 
     * @return the queueMsgCallback
     */
    public TunnelStreamQueueMsgCallback queueMsgCallback()
    {
        return _queueMsgCallback;
    }
    
    LoginRequest authLoginRequest()
    {
        return _authLoginRequest;
    }
    
    /**
     * The name of the TunnelStream.
     * 
     * @return the TunnelStream name
     */
    public String name()
    {
        return _name;
    }

    TunnelStreamManager tunnelStreamManager()
    {
        return _reactorChannel.tunnelStreamManager();
    }
    
    /**
     * A user specified object, possibly a closure. This information can be useful 
	 * for coupling this {@link TunnelStream} with other user created information, 
	 * such as a watch list associated with it.
     * @return the userSpecObject
     */
    public Object userSpecObject()
	{
		return _userSpecObject;
	}

    /**
     * The current known state of the tunnel stream, as indicated by the last
     * received response.
	 * @return the current state
     */
    public com.thomsonreuters.upa.codec.State state()
	{
		return _state;
	}
    
    /**
     * The reactor channel associated with the tunnel stream.
     * 
     * @return the reactor channel
     */
    public ReactorChannel reactorChannel()
    {
        return _reactorChannel;
    }
    
    /**
     * Returns whether or not this is a provider tunnel stream.
     */
    public boolean isProvider()
    {
        return _isProvider;
    }
    
    /* Returns whether or not XML tracing is enabled.  */
    boolean xmlTracing()
    {
        return _reactor._reactorOptions.xmlTracing();
    }

	void notifying(boolean notifying)
	{
		_notifying = notifying;
	}

	boolean notifying()
	{
		return _notifying;
	}
	
	int openStream(Error error)
	{
		_streamOpen = true;
		_firstIsSendWindowOpenCall = true;

		if (isProvider()) setupBufferPool();
		
		if (_tunnelStreamState != TunnelStreamState.NOT_OPEN)
		{
			error.errorId(ReactorReturnCodes.INVALID_USAGE);
			error.text("Tunnel stream is already opened.");
			return ReactorReturnCodes.INVALID_USAGE;
		}

		if (_reactorChannel.tunnelStreamManager().reactorChannel().channel() != null)
		 {
		    if (!isProvider()) // consumer tunnel stream
		    {
		        _tunnelStreamState = TunnelStreamState.SEND_REQUEST;
		    }
		    else // provider tunnel stream
		    {
                _recvLastSeqNumAckSent = _recvLastSeqNum;
		        _tunnelStreamState = TunnelStreamState.STREAM_OPEN;
		    }
		}
		else
			assert(_tunnelStreamState == TunnelStreamState.NOT_OPEN);
		
		_reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
		return ReactorReturnCodes.SUCCESS;
	}

	void setupBufferPool()
	{	
		_bufferPool = new SlicedBufferPool(_classOfService.common().maxMsgSize(), guaranteedOutputBuffers());
	}
	
    // For testing only
    void forceFileReset(boolean forceFileReset)
    {
        _forceFileReset = forceFileReset;
    }

    // For testing only
    boolean forceFileReset()
    {
        return _forceFileReset;
    }

    int openSubstream(QueueRequest requestMsg, Error error)
    {
        int ret = ReactorReturnCodes.SUCCESS;
        TunnelSubstream substreamSession = null;
        
        try
        {
            if (_classOfService.guarantee().type() != ClassesOfService.GuaranteeTypes.PERSISTENT_QUEUE)
            {
                return ReactorReturnCodes.FAILURE;
            }

            if (requestMsg.sourceName().length() > QueueMsgImpl.QMSG_MAX_NAME_LENGTH)
            {
                error.errorId(ReactorReturnCodes.PARAMETER_INVALID);
                error.text("sourceName is too long.");
                return ReactorReturnCodes.PARAMETER_INVALID;            	
            }

            
            if (!_streamIdtoQueueSubstreamTable.containsKey(requestMsg.streamId()))
            {
                if (_classOfService.guarantee().persistLocally())
                {
                    substreamSession = new TunnelSubstream(requestMsg.sourceName(), requestMsg.streamId(), 
                                                            requestMsg.domainType(), serviceId(),
                                                            _classOfService.guarantee().persistenceFilePath(), this, error);
                    updateTimeout(System.nanoTime());
                }
                else
                {
                    substreamSession = new TunnelSubstream(requestMsg.sourceName(), requestMsg.streamId(),
                                                                requestMsg.domainType(), serviceId(),
                                                                this, error);
                    _hasNextTimeout = false;
                }
                if (error.errorId() != ReactorReturnCodes.SUCCESS)
                    return error.errorId();
                
                // add to _streamIdtoQueueSubstreamTable
                _streamIdtoQueueSubstreamTable.put(requestMsg.streamId(), substreamSession);
                
                // send substream open request
                ret = substreamSession.sendSubstreamRequest(requestMsg, error);
            }
            else // cannot open same substream more than once
            {
                error.errorId(ReactorReturnCodes.INVALID_USAGE);
                error.text("Substream with stream id " + requestMsg.streamId() + " is already open");
                return ReactorReturnCodes.INVALID_USAGE;
            }
            
            if (_tunnelStreamState == TunnelStreamState.STREAM_OPEN)
            {
                _reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
            }
        }
        catch (Exception e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;
        }
        catch (InternalError e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;          
        }

        return ret;
    }
	
	int streamClosed(Error error)
	{
	    int ret = ReactorReturnCodes.SUCCESS;
	    
	    try
	    {
            /* Return TunnelStreamBuffers to pool. */
            TunnelStreamBuffer tunnelStreamBuffer;
            while ((tunnelStreamBuffer = _outboundTransmitList.pop(TunnelStreamBuffer.RETRANS_LINK)) != null)
                releaseBuffer(tunnelStreamBuffer, error);

            while ((tunnelStreamBuffer = _outboundMsgAckList.pop(TunnelStreamBuffer.RETRANS_LINK)) != null)
                releaseBuffer(tunnelStreamBuffer, error);


            _outboundTimeoutList.clear();
            _outboundImmediateList.clear();
    		_tunnelStreamState = TunnelStreamState.NOT_OPEN;
    		_sendLastSeqNum = 0;
    		_recvLastSeqNum = 0;
    		_recvLastSeqNumAckSent = 0;
    		_sendLastSeqNumNaked = 0;
    		_sendNakRangeList.count(0);
    		
    		_firstIsSendWindowOpenCall = false;
    

    	    for (TunnelSubstream substreamSession : _streamIdtoQueueSubstreamTable.values())
    	    {
    	        ret = substreamSession.close(error);
    	    }
            _streamIdtoQueueSubstreamTable.clear();
        }
        catch (Exception e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;
        }
        catch (InternalError e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;          
        }

	    return ret;
	}

	int close(boolean finalStatusEvent,  Error error)
	{
        _streamOpen =  false;
	    if (_tunnelStreamState == TunnelStreamState.NOT_OPEN)
	        return ReactorReturnCodes.SUCCESS;

		_finalStatusEvent = finalStatusEvent; 

        _reactorChannel.tunnelStreamManager().removeTunnelStreamFromTimeoutList(this);

		_reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);

		_tunnelStreamState = TunnelStreamState.SEND_FIN;
		_sendFinSeqNum = -1;
		_hasFinSent = false;

                
		return ReactorReturnCodes.SUCCESS;
	}

	int closeSubstream(int substreamId, Error error)
	{
		int ret;
		
		try
		{
    		TunnelSubstream substreamSession = _streamIdtoQueueSubstreamTable.get(substreamId);
    		if (substreamSession == null)
    		{
    			error.errorId(ReactorReturnCodes.INVALID_USAGE);
    			error.text("Substream is not open.");
    			return ReactorReturnCodes.INVALID_USAGE;
    		}
    
    		if ((ret = substreamSession.close(error)) != ReactorReturnCodes.SUCCESS)
    			return ret;
    
    		_reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
        }
        catch (Exception e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;
        }
        catch (InternalError e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;          
        }

		return ReactorReturnCodes.SUCCESS;
	}

	
	int releaseBuffer(TunnelStreamBuffer buffer, Error error)
	{
        buffer.persistenceBuffer(null, null);
		
        _bufferPool.releaseBufferSlice(buffer);
        _tunnelStreamBufferPool.push(buffer, TunnelStreamBuffer.RETRANS_LINK);
        
		return ReactorReturnCodes.SUCCESS;
	}
	
    TunnelStreamBuffer getBuffer(int length, boolean isForUser, boolean addTunnelStreamHeader, Error error)
    {
        TunnelStreamBuffer tunnelBuffer = null;
        int ret;
        
        if (length > _classOfService.common().maxMsgSize())
        {
            error.errorId(ReactorReturnCodes.INVALID_USAGE);
            error.text("Message size is too large.");
            return null;
        }

        if ((tunnelBuffer = _tunnelStreamBufferPool.pop(TunnelStreamBuffer.RETRANS_LINK)) == null)
            tunnelBuffer = new TunnelStreamBuffer();
        
        tunnelBuffer.clear(length);
        _bufferPool.getBufferSlice(tunnelBuffer, length + (addTunnelStreamHeader ? SlicedBufferPool.TUNNEL_STREAM_HDR_SIZE : 0), isForUser);
        
        if (tunnelBuffer.data() == null)
        {
            _tunnelStreamBufferPool.push(tunnelBuffer, TunnelStreamBuffer.RETRANS_LINK);
            error.errorId(ReactorReturnCodes.NO_BUFFERS);
            error.text("TunnelStream is out of buffers");
            return null;
        }

        if (addTunnelStreamHeader)
        {
            _encIter.clear();
            _encIter.setBufferAndRWFVersion(tunnelBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());
            
            _tunnelStreamHdr.clear();
            _tunnelStreamHdr.msgClass(MsgClasses.GENERIC);
            /* Use channel-facing stream ID (so message can go directly to the channel even if watchlist is enabled. */
            _tunnelStreamHdr.streamId(_channelStreamId);
            _tunnelStreamHdr.domainType(domainType());
            _tunnelStreamHdr.containerType(DataTypes.MSG);
            _tunnelStreamHdr.applyHasExtendedHdr();
            _tunnelStreamHdr.applyMessageComplete();
            _tunnelStreamHdr.applyHasSeqNum();
            _tunnelStreamHdr.seqNum(_sendLastSeqNum); // placeholder, needs to be replaced immediately before sending
            tunnelBuffer.seqNum(_sendLastSeqNum); // placeholder, needs to be replaced immediately before sending
    
            if ((ret = _tunnelStreamHdr.encodeInit(_encIter,  0)) != CodecReturnCodes.ENCODE_EXTENDED_HEADER)
            {
                releaseBuffer(tunnelBuffer, error);
                error.errorId(ret);
                error.text("Unable to encode TunnelStream header");
                return null;
            }
            
            if ((ret = _encIter.encodeNonRWFInit(_tmpBuffer)) != CodecReturnCodes.SUCCESS)
            {
                releaseBuffer(tunnelBuffer, error);
                error.errorId(ret);
                error.text("Unable to encode TunnelStream header");
                return null;
            }
            
            if (_tmpBuffer.length() < 1)
            {
                releaseBuffer(tunnelBuffer, error);
                error.errorId(ReactorReturnCodes.FAILURE);
                error.text("Unable to encode TunnelStream header");
               return null;
            }
            
            _tmpBuffer.data().put((byte)OpCodes.DATA);
    
            if ((ret = _encIter.encodeNonRWFComplete(_tmpBuffer, true)) != CodecReturnCodes.SUCCESS)
            {
                releaseBuffer(tunnelBuffer, error);
                error.errorId(ret);
                error.text("Unable to encode TunnelStream header");
                return null;
            }
    
            if ((ret =_tunnelStreamHdr.encodeExtendedHeaderComplete(_encIter,  true)) < CodecReturnCodes.SUCCESS)
            {
                releaseBuffer(tunnelBuffer, error);
                error.errorId(ret);
                error.text("Unable to encode TunnelStream header");
                return null;
            }

            // set TunnelStream header length on buffer
            tunnelBuffer.tunnelStreamHeaderLen(tunnelBuffer.length());
            
            // set ByteBuffer limit to requested length plus position after TunnelStream header encode
            tunnelBuffer.setCurrentPositionAsEndOfEncoding();
            tunnelBuffer.setToInnerWriteBuffer();
        }

        return tunnelBuffer;
    }
    
    int submit(TunnelStreamBuffer tunnelBuffer, int containerType,  Error error)
    {
        int ret;
        long timeout, currentTimeNsec;
        TunnelSubstream substreamSession = null;
        
        try
        {
            // tunnel stream must be in open state to submit
            if (_tunnelStreamState != TunnelStreamState.STREAM_OPEN)
            {
                error.text("TunnelStream is not in the open state");
                error.errorId(ReactorReturnCodes.FAILURE);
                return ReactorReturnCodes.FAILURE;                
            }
            
            // reject submit if buffer is larger than negotiated max message size
            if (((TunnelStreamBuffer)tunnelBuffer).length() > _classOfService.common().maxMsgSize())
            {
                error.errorId(ReactorReturnCodes.PARAMETER_INVALID);
                error.text("Submitted buffer cannot be larger than maxMsgSize of " + _classOfService.common().maxMsgSize());
                return ReactorReturnCodes.PARAMETER_INVALID;                
            }
            
            tunnelBuffer.setCurrentPositionAsEndOfEncoding();
            
            // first decode TunnelStream message and substream message
            if (containerType == DataTypes.MSG)
            {
                tunnelBuffer.setAsInnerReadBuffer();
                if (decodeSubstreamMsg(tunnelBuffer, error) < CodecReturnCodes.SUCCESS)
                {
                    return ReactorReturnCodes.INVALID_ENCODING;
                }
                substreamSession = _streamIdtoQueueSubstreamTable.get(_decSubMsg.streamId());
            }

            /* Set to full buffer length. */
            tunnelBuffer.setToFullWritebuffer();
            
            if (substreamSession != null) // queue message
            {
                    
                // now set required info on BufferImpl
                switch (_decSubMsg.msgClass())
                {
                    case MsgClasses.GENERIC:

                        _queueData.clear();
                        _queueData.decode(_decSubIter, _decSubMsg);
                        
                        // substream must be in the OPEN state to submit data
                        if (substreamSession._state != TunnelSubstreamState.OPEN)
                        {
                            error.errorId(ReactorReturnCodes.FAILURE);
                            error.text("Substream is not in open state");
                            return ReactorReturnCodes.FAILURE;                        
                        }
                        
                        if (_queueData.sourceName().length() > QueueMsgImpl.QMSG_MAX_NAME_LENGTH )
                        {
                            error.errorId(ReactorReturnCodes.PARAMETER_INVALID);
                            error.text("sourceName is too long.");
                            return ReactorReturnCodes.PARAMETER_INVALID;            	
                        }

                        if (_queueData.destName().length() > QueueMsgImpl.QMSG_MAX_NAME_LENGTH )
                        {
                            error.errorId(ReactorReturnCodes.PARAMETER_INVALID);
                            error.text("destName is too long.");
                            return ReactorReturnCodes.PARAMETER_INVALID;            	
                        }
                        tunnelBuffer.containerType(_queueData.containerType());
                        
                        timeout = _queueData.timeout();
                        if (timeout > 0)
                        {
                            currentTimeNsec = System.nanoTime();
                            tunnelBuffer.timeoutIsCode(false);
                            tunnelBuffer.timeQueuedNsec(currentTimeNsec);
                            tunnelBuffer.timeoutNsec(timeout * TunnelStreamUtil.NANO_PER_MILLI + currentTimeNsec);
                        }
                        else
                        {
                            currentTimeNsec = 0;
                            tunnelBuffer.timeoutIsCode(true);
                            tunnelBuffer.timeoutNsec(timeout);
                        }

                        if ((ret = substreamSession.saveMsg(tunnelBuffer, error))
                                < ReactorReturnCodes.SUCCESS)
                            return ret;
                        
                        /* Add to timeout list, if a numerical timeout was specified. */
                        if (timeout > 0)
                        {
                            insertTimeoutBuffer(tunnelBuffer, currentTimeNsec);
                            _reactorChannel.tunnelStreamManager().addTunnelStreamToTimeoutList(this, _nextTimeoutNsec);
                        }
                        else if (timeout == QueueDataTimeoutCode.IMMEDIATE)
                        {
                            _outboundImmediateList.push(tunnelBuffer, TunnelStreamBuffer.TIMEOUT_LINK);
                        }

                        /* Remember that this is a QueueData message. */
                        tunnelBuffer.isQueueData(true);
                        _outboundTransmitList.push(tunnelBuffer, TunnelStreamBuffer.RETRANS_LINK);
                        break;
                    case MsgClasses.CLOSE:
                        /* Remember that this message ends the queue stream
                         * (so the persistence file, if one is open, should be closed). */
                        tunnelBuffer.isQueueClose(true);
                        tunnelBuffer.persistenceBuffer(substreamSession, null);
                        _outboundTransmitList.push(tunnelBuffer, TunnelStreamBuffer.RETRANS_LINK);

                        /* As far as application is concerned, queue stream is closed. */
                        _streamIdtoQueueSubstreamTable.remove(substreamSession._streamId);
                        break;
                        
                    default:
                        error.errorId(ReactorReturnCodes.FAILURE);
                        error.text("Submitted buffer sub MsgClass invalid: " + _decSubMsg.msgClass());
                        return ReactorReturnCodes.FAILURE;
                }
                    
                
                error.text("");
                error.errorId(ReactorReturnCodes.SUCCESS);
    
                if (_tunnelStreamState == TunnelStreamState.STREAM_OPEN
                        /* If stream isn't open, expire any messages with immediate timeout. */
                        || _tunnelStreamState == TunnelStreamState.NOT_OPEN && _outboundImmediateList.count() > 0)
                {
                    _reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
                    return 1;
                }
                else
                    return ReactorReturnCodes.SUCCESS;
            }
            else // non-Queue message
            {
                // check first if buffer is an encoded QueueRequest
                // if it is, open a substream
                if (containerType == DataTypes.MSG &&
                    _classOfService.guarantee().type() == ClassesOfService.GuaranteeTypes.PERSISTENT_QUEUE &&
                    _decSubMsg.domainType() != DomainTypes.LOGIN &&
                    _decSubMsg.domainType() != DomainTypes.SOURCE &&
                    _decSubMsg.domainType() != DomainTypes.DICTIONARY &&
                    _decSubMsg.domainType() != DomainTypes.SYMBOL_LIST)
                {
                    if (_decSubMsg.msgClass() == MsgClasses.REQUEST)
                    {
                        // buffer is an encoded QueueRequest, open substream
                        _queueRequest.clear();
                        _queueRequest.decode(_decSubIter, _decSubMsg);
                        
                        // free buffer
                        releaseBuffer(tunnelBuffer, error);
                        
                        error.text("");
                        error.errorId(ReactorReturnCodes.SUCCESS);
    
                        // open substream
                        return openSubstream(_queueRequest, error);
                    }
                    else // reject any QueueMsg that is not a QueueRequest
                    {
                        error.errorId(ReactorReturnCodes.FAILURE);
                        error.text("No Queue open for stream id: " + _decSubMsg.streamId());
                        return ReactorReturnCodes.FAILURE;            
                    }
                }
                else // buffer is NOT an encoded QueueRequest
                {
                    /* Don't allow provider tunnel streams to submit messages
                     * until login refresh is sent by provider.
                     */
                    if (isProvider() &&
                        _classOfService.authentication().type() == ClassesOfService.AuthenticationTypes.OMM_LOGIN &&
                        !_providerLoginRefreshSent)
                    {
                        /* if this is login refresh, let refresh through
                         * and set _providerLoginRefreshSent flag to true */
                        if (_decSubMsg.domainType() != DomainTypes.LOGIN ||
                            _decSubMsg.msgClass() != MsgClasses.REFRESH)
                        {
                            error.errorId(ReactorReturnCodes.FAILURE);
                            error.text("Authentication needs to complete before submitting other data");
                            return ReactorReturnCodes.FAILURE;                                    
                        }
                        else
                        {
                            _providerLoginRefreshSent = true;
                        }
                    }
                    
                    // replace container type if not a message
                    if (containerType != DataTypes.MSG)
                    {
                        tunnelBuffer.setAsFullReadBuffer();
                        tunnelBuffer.data().put(tunnelBuffer.data().position() + CONTAINER_TYPE_POSITION,
                                              (byte)(containerType - DataTypes.CONTAINER_TYPE_MIN));
                    }

                    tunnelBuffer.setToFullWritebuffer();
                    _outboundTransmitList.push(tunnelBuffer, TunnelStreamBuffer.RETRANS_LINK);
                    
                    error.text("");
                    error.errorId(ReactorReturnCodes.SUCCESS);
    
                    if (_tunnelStreamState == TunnelStreamState.STREAM_OPEN)
                    {
                        _reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
                        return 1;
                    }
                    else
                    {
                        return ReactorReturnCodes.SUCCESS;
                    }
                }
            }
        }
        catch (Exception e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;
        }
        catch (InternalError e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;          
        }
    }

    private int decodeMsg(TunnelStreamBuffer buffer, Error error)
    {
        // decode TunnelStream message first
        _decIter.clear();
        _decIter.setBufferAndRWFVersion(buffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());
        _decMsg.clear();
        
        return (_decMsg.decode(_decIter));
    }

	private int decodeSubstreamMsg(TunnelStreamBuffer buffer, Error error)
    {
	    _decSubIter.clear();
        _decSubIter.setBufferAndRWFVersion(buffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());                

        return _decSubMsg.decode(_decSubIter);
    }

	int dispatch(Error error)
	{
		int ret = ReactorReturnCodes.SUCCESS;
		
		try
		{
    		// if buffer is waiting due to WRITE_CALL_AGAIN, send it
    		if (_writeCallAgainBuffer != null)
    		{
    		    if ((ret = writeChannelBuffer(_writeCallAgainBuffer, error)) < ReactorReturnCodes.SUCCESS)
    		        return ret;
    		}
        		
    		switch(_tunnelStreamState)
    		{
    			case NOT_OPEN:
    
                    _reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);
    
                    if (_streamOpen && _reactorChannel.tunnelStreamManager().reactorChannel().channel() != null)
    				{
                        _tunnelStreamState = TunnelStreamState.SEND_REQUEST;
    					/* Fall through and send request */
    				}
    				else
    					return ReactorReturnCodes.SUCCESS;
    
    			case SEND_REQUEST:
    			{
    				/* Send a request message to open the initial tunnel stream. */
    
    				TunnelStreamMsg.TunnelStreamRequest requestHeader = (TunnelStreamMsg.TunnelStreamRequest)_tunnelStreamMsg;
    
    				requestHeader.clearRequest();
    				_tunnelStreamMsg.streamId(_streamId);
    				_tunnelStreamMsg.domainType(_domainType);
    				_tunnelStreamMsg.serviceId(_serviceId);
    				if (_name != null)
    				{
    				    _tunnelStreamMsg.name(_name);
    				}
    				else
    				{
    				    _tunnelStreamMsg.name("TunnelStream");
    				}
    				_tunnelStreamMsg.classOfService(_classOfService);
    
    				
                    if (_reactorChannel.watchlist() == null)
                    {
                        /* Message can go directly to channel. */
                        TransportBuffer tBuffer = getChannelBuffer(requestHeader.requestBufferSize(), false, error);
                        
                        if (tBuffer == null)
                            return error.errorId();
                        
                        _encIter.clear();
                        _encIter.setBufferAndRWFVersion(tBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());
    
                        if ((ret = requestHeader.encodeRequest(_encIter, (RequestMsg)_encMsg)) != CodecReturnCodes.SUCCESS)
                        {
                            error.errorId(ret);
                            error.text("Failed to encode TunnelStream request message.");
                            return ReactorReturnCodes.FAILURE;
                        }
    
                        if ((_traceFlags & TunnelStreamTraceFlags.MSGS) > 0)
                            traceBufferToXml(tBuffer);

                        /* Write full message to channel. */
                        if ((ret = writeChannelBuffer(tBuffer, error)) != ReactorReturnCodes.SUCCESS)
                            return ret;
    
                        if (ret > TransportReturnCodes.SUCCESS)
                            _reactorChannel.tunnelStreamManager().setNeedsFlush();
                    }
                    else
                    {
                        /* Submit Msg to watchlist (with the ClassOfService set as payload). */
                        _reactorChannel.tunnelStreamManager()._tunnelStreamTempByteBuffer.clear();
                        _reactorChannel.tunnelStreamManager()._tunnelStreamTempBuffer.data(_reactorChannel.tunnelStreamManager()._tunnelStreamTempByteBuffer);
                         if ((ret = requestHeader.encodeRequestAsMsg(_encIter, _reactorChannel.tunnelStreamManager()._tunnelStreamTempBuffer, (RequestMsg)_encMsg)) != CodecReturnCodes.SUCCESS)
                        {
                            error.errorId(ret);
                            error.text("Failed to encode TunnelStream request message.");
                            return ReactorReturnCodes.FAILURE;
                        }
                         
                        if ((_traceFlags & TunnelStreamTraceFlags.MSGS) > 0)
                            traceMsgtoXml(_traceMsg, true);

                        _reactorSubmitOptions.clear();
                        if  ((ret = _reactorChannel.submit(_encMsg, _reactorSubmitOptions, _errorInfo)) < ReactorReturnCodes.SUCCESS)
                        {
                            errorInfoToError(_errorInfo, error);
                            return ret;
                        }
                    }
    
    				_tunnelStreamState = TunnelStreamState.WAITING_REFRESH;
    				_reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);

                    /* Start request timer. */
    				_nextTimeoutNsec = (_responseTimeout * TunnelStreamUtil.NANO_PER_SEC) + System.nanoTime();
                    _reactorChannel.tunnelStreamManager().addTunnelStreamToTimeoutList(this, _nextTimeoutNsec);                     
    				return ReactorReturnCodes.SUCCESS;
    			}
    
    			case WAITING_REFRESH:
    				_reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);
    				return ReactorReturnCodes.SUCCESS;
    
    			case STREAM_OPEN:
    			{
                    /* Check if we can send stream acknowledgements. */
                    if ( _recvLastSeqNum != _recvLastSeqNumAckSent
                            || 
                            /* Or do we need retransmissions? */
                            _sendNakRangeList.count() > 0)
                    {
                        /* Acknowledge some received data or request retransmission. */
    
                        TunnelStreamMsg.TunnelStreamAck ackHeader = (TunnelStreamMsg.TunnelStreamAck)_tunnelStreamMsg;
    
                        ackHeader.clearAck();
                        /* Use channel-facing stream ID (so message can go directly to the channel even if watchlist is enabled. */
                        _tunnelStreamMsg.streamId(_channelStreamId);
                        _tunnelStreamMsg.domainType(_domainType);;
                        ackHeader.seqNum(_recvLastSeqNum);
                        ackHeader.recvWindow(_classOfService.flowControl().recvWindowSize());
    
                        TransportBuffer tBuffer = getChannelBuffer(ackHeader.ackBufferSize(_sendNakRangeList), false, error);
    
                        if (tBuffer == null)
                            return error.errorId();
    
                        /* Encode generic message header. */
    
                        _encIter.clear();
                        _encIter.setBufferAndRWFVersion(tBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());
    
                        if ((ret = ackHeader.encodeAck(_encIter, null, _sendNakRangeList, 0)) != CodecReturnCodes.SUCCESS)
                        {
                            error.errorId(ret);
                            error.text("Failed to encode TunnelStream ACK message.");
                            releaseChannelBuffer(tBuffer, error);
                            return ReactorReturnCodes.FAILURE;
                        }
                        
                        if ((_traceFlags & TunnelStreamTraceFlags.ACTIONS) > 0)
                            System.out.println("<!-- TunnelTrace: Writing ack. Ack state: "
                                    + _recvLastSeqNum + " in, "
                                    + _recvLastSeqNum + " acked in, "
                                    + _sendLastSeqNum + " out, "
                                    + _sendLastSeqNumAcked +  " acked out, "
                                    + _classOfService.flowControl().recvWindowSize() + " recvWindow -->");
    
                        if ((_traceFlags & TunnelStreamTraceFlags.MSGS) > 0)
                            traceBufferToXml(tBuffer);
    
                        /* Write full message to channel. */
                        if ((ret = writeChannelBuffer(tBuffer, error)) != ReactorReturnCodes.SUCCESS)
                            return ret;
    
                        _recvLastSeqNumAckSent = _recvLastSeqNum;
    
                        if (ret > TransportReturnCodes.SUCCESS)
                            _reactorChannel.tunnelStreamManager().setNeedsFlush();
    
                        /* Reset nak ranges, if nak ranges were sent. */
                        _sendNakRangeList.count(0);
                    }
                    
                    if ((ret = handleTransmit(error)) != ReactorReturnCodes.SUCCESS)
                    {
                    	return ret;
                    }
                    
                    if (_writeCallAgainBuffer != null)
                    {
                        _reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
                    }
                    else
                        _reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);

                    updateTimeout(System.nanoTime());
    
                    return ReactorReturnCodes.SUCCESS;
    			}    
    			case CLOSING:
    			{
    				if (!isProvider()) // consumer
    				{
    					ret = sendCloseMsg(error);    
    				
    					_reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);
    					_tunnelStreamState = TunnelStreamState.NOT_OPEN;   
    					_finAckWaitCount = 0;
    					if (_finalStatusEvent)
    					{
    						if (error.text() == null ) 
    							error.text("Completed");
    						_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusCloseRecover(this, error);
    					}
    				}
    				else // provider
    				{    				
    					ret = sendCloseMsg(error);  // there is provider and consumer switch 
    					_tunnelStreamState = TunnelStreamState.NOT_OPEN;
    					_finAckWaitCount = 0;
    					if (_finalStatusEvent)
    					{
       						if (error.text() == null ) 
    							error.text("Completed");
    						_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusClose(this, error);
    					}
    				}
    				
                    for (Integer substreamId : _streamIdtoQueueSubstreamTable.keySet())
                    {
                        if ((ret = closeSubstream(substreamId.intValue(), error)) != ReactorReturnCodes.SUCCESS)
                            return ret;
                    }
                    _streamIdtoQueueSubstreamTable.clear();
    		        
                    return CodecReturnCodes.SUCCESS;
    			}  
    			case SEND_FIN:    			
    				if (( ret = handleTransmit(error)) != CodecReturnCodes.SUCCESS)
    				{
                        error.errorId(ret);
                        error.text("Failed to handle retransmit");
                        
                        return ReactorReturnCodes.FAILURE;    					    					
    				}
    				
                    _reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);   
                    long currentTimeNsec = System.nanoTime();
                    _nextTimeoutNsec = ((long)Math.pow(2, _finAckWaitCount)* finAckTimeout) * TunnelStreamUtil.NANO_PER_MILLI + currentTimeNsec;
                                        
    				
                    TunnelStreamMsg.TunnelStreamAck ackHeader = (TunnelStreamMsg.TunnelStreamAck)_tunnelStreamMsg;
                    
                    ackHeader.clearAck();
                    /* Use channel-facing stream ID (so message can go directly to the channel even if watchlist is enabled. */
                    _tunnelStreamMsg.streamId(_channelStreamId);
                    _tunnelStreamMsg.domainType(_domainType);
                   
                    if (_hasFinSent) 
                    {
                    	 ackHeader.seqNum(_sendFinSeqNum);
                    }
                    else
                    {
                    	_sendFinSeqNum = ++_sendLastSeqNum;
                    	ackHeader.seqNum(_sendLastSeqNum);                    	
                    	_hasFinSent = true;
                    }
                    ackHeader.recvWindow(_classOfService.flowControl().recvWindowSize());

                    TransportBuffer tBuffer = getChannelBuffer(ackHeader.ackBufferSize(_sendNakRangeList), false, error);

                    if (tBuffer == null)
                        return error.errorId();

                    /* Encode generic message header. */

                    _encIter.clear();
                    _encIter.setBufferAndRWFVersion(tBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());

                    if ((ret = ackHeader.encodeAck(_encIter, null, null, _ackOpcodeFin)) != CodecReturnCodes.SUCCESS)
                    {
                        error.errorId(ret);
                        error.text("Failed to encode TunnelStream ACK message.");
                        releaseChannelBuffer(tBuffer, error);
                        return ReactorReturnCodes.FAILURE;
                    }    	
                       
                    _tunnelStreamState = TunnelStreamState.WAIT_FIN_ACK;
   				
                    if ((ret = writeChannelBuffer(tBuffer, error)) != ReactorReturnCodes.SUCCESS)
                        return ret;

                    return ReactorReturnCodes.SUCCESS;
                    
    			case SEND_FIN_ACK:       				  
    				if (( ret = handleTransmit(error)) != CodecReturnCodes.SUCCESS)
    				{
                        error.errorId(ret);
                        error.text("Failed to handle retransmit");
                        return ReactorReturnCodes.FAILURE;    					    					
    				}
    				if ( !isSendWindowOpen(null)) 
    					return ReactorReturnCodes.SUCCESS;
    				       				
                    ackHeader = (TunnelStreamMsg.TunnelStreamAck)_tunnelStreamMsg;
                    
                    ackHeader.clearAck();
                    /* Use channel-facing stream ID (so message can go directly to the channel even if watchlist is enabled). */
                    _tunnelStreamMsg.streamId(_channelStreamId);
                    _tunnelStreamMsg.domainType(_domainType);
                    ackHeader.seqNum(_recvLastSeqNum);
                    ackHeader.recvWindow(_classOfService.flowControl().recvWindowSize());

                    tBuffer = getChannelBuffer(ackHeader.ackBufferSize(_sendNakRangeList), false, error);

                    if (tBuffer == null)
                        return error.errorId();

                    /* Encode generic message header. */

                    _encIter.clear();
                    _encIter.setBufferAndRWFVersion(tBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());

                    if ((ret = ackHeader.encodeAck(_encIter, null, _sendNakRangeList, 0)) != CodecReturnCodes.SUCCESS)
                    {
                        error.errorId(ret);
                        error.text("Failed to encode TunnelStream ACK message.");
                        releaseChannelBuffer(tBuffer, error);
                        return ReactorReturnCodes.FAILURE;
                    }                      
                    if ((ret = writeChannelBuffer(tBuffer, error)) != ReactorReturnCodes.SUCCESS)
                        return ret;    				
                    
                  //////////////////////////////////////////////////////////////////////////
                  // Send final FIN   SEND_FINAL_FIN 
                    ackHeader = (TunnelStreamMsg.TunnelStreamAck)_tunnelStreamMsg;
                    
                    ackHeader.clearAck();
                    /* Use channel-facing stream ID (so message can go directly to the channel even if watchlist is enabled). */
                    _tunnelStreamMsg.streamId(_channelStreamId);
                    _tunnelStreamMsg.domainType(_domainType);
                    
                    if (_hasFinSent) 
                    {
                    	 ackHeader.seqNum(_sendFinSeqNum);
                    }
                    else
                    {   
                    	_sendFinSeqNum = ++_sendLastSeqNum;
                    	ackHeader.seqNum(_sendLastSeqNum);
                    	_hasFinSent = true;
                    }
                    ackHeader.recvWindow(_classOfService.flowControl().recvWindowSize());

                    tBuffer = getChannelBuffer(ackHeader.ackBufferSize(_sendNakRangeList), false, error);

                    if (tBuffer == null)
                        return error.errorId();

                    /* Encode generic message header. */

                    _encIter.clear();
                    _encIter.setBufferAndRWFVersion(tBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());

                    if ((ret = ackHeader.encodeAck(_encIter, null, null, _ackOpcodeFin)) != CodecReturnCodes.SUCCESS)
                    {
                        error.errorId(ret);
                        error.text("Failed to encode TunnelStream ACK message.");
                        releaseChannelBuffer(tBuffer, error);
                        return ReactorReturnCodes.FAILURE;
                    }    	
                                                               
                    _tunnelStreamState = TunnelStreamState.WAIT_FINAL_FIN_ACK;
                    
                    if ((ret = writeChannelBuffer(tBuffer, error)) != ReactorReturnCodes.SUCCESS)
                        return ret;

                    _reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);                                                           
                    currentTimeNsec = System.nanoTime();
                    _finAckWaitCount = 0;
                    _nextTimeoutNsec = ((long)Math.pow(2, _finAckWaitCount)) * finAckTimeout * TunnelStreamUtil.NANO_PER_MILLI + currentTimeNsec;
                    _reactorChannel.tunnelStreamManager().addTunnelStreamToTimeoutList(this, _nextTimeoutNsec);                     
                    return ReactorReturnCodes.SUCCESS;
                    
       			case SEND_FINAL_FIN:    // subset of above too 
       				
    				if (( ret = handleTransmit(error)) != CodecReturnCodes.SUCCESS)
    				{
                        error.errorId(ret);
                        error.text("Failed to handle retransmit");
                        return ReactorReturnCodes.FAILURE;    					    					
    				}
    				if ( !isSendWindowOpen(null)) 
    					return ReactorReturnCodes.SUCCESS;
    				
    				
                    ackHeader = (TunnelStreamMsg.TunnelStreamAck)_tunnelStreamMsg;
                    
                    ackHeader.clearAck();
                    /* Use channel-facing stream ID (so message can go directly to the channel even if watchlist is enabled). */
                    _tunnelStreamMsg.streamId(_channelStreamId);
                    _tunnelStreamMsg.domainType(_domainType);
                    
                    if (_hasFinSent)
                    {
                    	ackHeader.seqNum(_sendFinSeqNum);
                    }
                    else
                    {
                    	ackHeader.seqNum(_sendLastSeqNum);
                    }
                    
                    ackHeader.recvWindow(_classOfService.flowControl().recvWindowSize());

                    tBuffer = getChannelBuffer(ackHeader.ackBufferSize(_sendNakRangeList), false, error);

                    if (tBuffer == null)
                        return error.errorId();

                    /* Encode generic message header. */

                    _encIter.clear();
                    _encIter.setBufferAndRWFVersion(tBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());

                    if ((ret = ackHeader.encodeAck(_encIter, null, null, _ackOpcodeFin)) != CodecReturnCodes.SUCCESS)
                    {
                        error.errorId(ret);
                        error.text("Failed to encode TunnelStream ACK message.");
                        releaseChannelBuffer(tBuffer, error);
                        return ReactorReturnCodes.FAILURE;
                    }    	
                                                               
                    _tunnelStreamState = TunnelStreamState.WAIT_FINAL_FIN_ACK;
                    
                    if ((ret = writeChannelBuffer(tBuffer, error)) != ReactorReturnCodes.SUCCESS)
                        return ret;

                    _reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);              
                    
                    currentTimeNsec = System.nanoTime();
                    _nextTimeoutNsec = ((long)Math.pow(2, _finAckWaitCount)) * finAckTimeout * TunnelStreamUtil.NANO_PER_MILLI + currentTimeNsec;
                    _reactorChannel.tunnelStreamManager().addTunnelStreamToTimeoutList(this, _nextTimeoutNsec);                     
                    return ReactorReturnCodes.SUCCESS;
                                                            
       			case SEND_FINAL_FIN_ACK_AND_CLOSING: 
       				
    				if (( ret = handleTransmit(error)) != CodecReturnCodes.SUCCESS)
    				{
                        error.errorId(ret);
                        error.text("Failed to handle retransmit");
                        return ReactorReturnCodes.FAILURE;    					    					
    				}
    				if ( !isSendWindowOpen(null))
    					return ReactorReturnCodes.SUCCESS;
    				    				
                    ackHeader = (TunnelStreamMsg.TunnelStreamAck)_tunnelStreamMsg;
                    
                    ackHeader.clearAck();
                    /* Use channel-facing stream ID (so message can go directly to the channel even if watchlist is enabled). */
                    _tunnelStreamMsg.streamId(_channelStreamId);
                    _tunnelStreamMsg.domainType(_domainType);
                    ackHeader.seqNum(_receivedFinalFinSeqNum);
                    ackHeader.recvWindow(_classOfService.flowControl().recvWindowSize());
                    
                    tBuffer = getChannelBuffer(ackHeader.ackBufferSize(_sendNakRangeList), false, error);

                    if (tBuffer == null)
                        return error.errorId();

                    /* Encode generic message header. */

                    _encIter.clear();
                    _encIter.setBufferAndRWFVersion(tBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());

                    if ((ret = ackHeader.encodeAck(_encIter, null, _sendNakRangeList, 0)) != CodecReturnCodes.SUCCESS)
                    {
                        error.errorId(ret);
                        error.text("Failed to encode TunnelStream ACK message.");
                        releaseChannelBuffer(tBuffer, error);
                        return ReactorReturnCodes.FAILURE;
                    }                      
                    if ((ret = writeChannelBuffer(tBuffer, error)) != ReactorReturnCodes.SUCCESS)
                        return ret;
                    

       				if (!isProvider()) // consumer
    				{
    					ret = sendCloseMsg(error);    
    				
    					_reactorChannel.tunnelStreamManager().removeTunnelStreamFromDispatchList(this);
    					_tunnelStreamState = TunnelStreamState.NOT_OPEN;   
    					_finAckWaitCount = 0;
    					if (_finalStatusEvent)
    						_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusCloseRecover(this, error);
    					else
    					    _reactorChannel.tunnelStreamManager().removeTunnelStream(this);
    					return CodecReturnCodes.SUCCESS;
    				}
    				else // provider
    				{    				
    					ret = sendCloseMsg(error);  // there is provider and consumer switch 
    					_tunnelStreamState = TunnelStreamState.NOT_OPEN;
    					_finAckWaitCount = 0;
    					if (_finalStatusEvent)
    						_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusClose(this, error);
                        else
                            _reactorChannel.tunnelStreamManager().removeTunnelStream(this);
    				
    					return CodecReturnCodes.SUCCESS;
    				}                                        
    			case WAIT_FIN_ACK:               				

    				currentTimeNsec = System.nanoTime();
    				if ( currentTimeNsec > nextTimeoutNsec()) 
    				{
    					
    					
    					_tunnelStreamState = TunnelStreamState.SEND_FIN;
    					_finAckWaitCount ++;
    					_reactorChannel.tunnelStreamManager().removeTunnelStreamFromTimeoutList(this);
    					if (_finAckWaitCount > _max_num_timeout_retry)
    					{
    						_tunnelStreamState = TunnelStreamState.CLOSING;       	                           	                   
    					}
    				}
    				return ReactorReturnCodes.SUCCESS;

    			case WAIT_FINAL_FIN_ACK:	
    			{	 
    				currentTimeNsec = System.nanoTime();
    				if ( currentTimeNsec > nextTimeoutNsec()) 
    				{ 		
    					_reactorChannel.tunnelStreamManager().removeTunnelStreamFromTimeoutList(this);
    					_finAckWaitCount ++;
    					
    					_tunnelStreamState = TunnelStreamState.SEND_FINAL_FIN;
       					if (_finAckWaitCount > _max_num_timeout_retry )
    					{
    						_tunnelStreamState = TunnelStreamState.CLOSING;       	                           	                   
    					}
    				}
    				return ReactorReturnCodes.SUCCESS;        				
    			}	    		                    
    			default:
    				error.errorId(ReactorReturnCodes.FAILURE);
    				error.text("Unknown state.");
    				return ReactorReturnCodes.FAILURE;
    		}
        }
        catch (Exception e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;
        }
		catch (InternalError e)
		{
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;		    
		}
	}

	
    /* Send any data waiting to be transmitted, if possible. */
	int handleTransmit(Error error)
	{
		int ret = ReactorReturnCodes.SUCCESS;
		TunnelStreamBuffer tunnelBuffer;
	
		while((tunnelBuffer = _outboundTransmitList.peek()) != null)
		{
            if (_writeCallAgainBuffer != null)
                break;

            if (!isSendWindowOpen(tunnelBuffer))
                break;

            if (tunnelBuffer.isRetransmit())
            {
                assert(tunnelBuffer.isTransmitted());
                tunnelBuffer.setToFullWritebuffer();
                // replace opcode for retransmission
                if (decodeMsg(tunnelBuffer, error) < CodecReturnCodes.SUCCESS)
                {
                    return ReactorReturnCodes.INVALID_ENCODING;
                }
                _decMsg.extendedHeader().data().put(_decMsg.extendedHeader().position(), (byte)OpCodes.RETRANS);
            }
            else if (!tunnelBuffer.isTransmitted())
            {
                /* If persisting, mark message as transmitted so we don't time it out 
                 * if the queue stream is closed and re-opened. */
                if (tunnelBuffer.tunnelSubstream() != null)
                {
                    if (tunnelBuffer.isQueueData())
                    {
                        if ((ret = tunnelBuffer.tunnelSubstream().setBufferAsTransmitted(tunnelBuffer, error))
                                != ReactorReturnCodes.SUCCESS)
                            return ret;

                        tunnelBuffer.isTransmitted(true);

                        /* Do not process time for this message anymore. */
                        if (!tunnelBuffer.timeoutIsCode())
                            _outboundTimeoutList.remove(tunnelBuffer, TunnelStreamBuffer.TIMEOUT_LINK);
                        else if (tunnelBuffer.timeoutNsec() == QueueDataTimeoutCode.IMMEDIATE)
                            _outboundImmediateList.remove(tunnelBuffer, TunnelStreamBuffer.TIMEOUT_LINK);
                    }
                    else if (tunnelBuffer.isQueueClose())
                    {
                        /* Queue close is next to go on the network. Ensure persistence file 
                         * (if open) is closed. */
                        if ((ret = tunnelBuffer.tunnelSubstream().close(error))
                                != ReactorReturnCodes.SUCCESS)
                            return ret;

                        tunnelBuffer.persistenceBuffer(null, null);
                    }
                }

                ++_sendLastSeqNum;

                // replace TunnelStream seqNum
                tunnelBuffer.seqNum(_sendLastSeqNum);
                tunnelBuffer.setAsFullReadBuffer();
                _encIter.clear();
                _encIter.setBufferAndRWFVersion(tunnelBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());
                if ((ret = _encIter.replaceSeqNum(tunnelBuffer.seqNum())) != CodecReturnCodes.SUCCESS)
                {
                    error.errorId(ret);
                    error.text("Failed to update sequence number on TunnelStream header.");
                    return ReactorReturnCodes.FAILURE;
                }
            }

            // copy TunnelStream buffer to TransportBuffer
            tunnelBuffer.setToFullWritebuffer();
            TransportBuffer tBuffer = getChannelBuffer(tunnelBuffer.length(), false, error);
            if (tBuffer == null)
                return error.errorId();
            tunnelBuffer.copyFullBuffer(tBuffer.data());

            if ((_traceFlags & TunnelStreamTraceFlags.ACTIONS) > 0)
                System.out.printf("<!-- TunnelTrace: Writing message sentBytes: %d -->\n", _sendBytes);

            if ((_traceFlags & TunnelStreamTraceFlags.MSGS) > 0)
                traceBufferToXml(tBuffer);

            /* Write full message to channel. */
            if ((ret = writeChannelBuffer(tBuffer, error)) != ReactorReturnCodes.SUCCESS)
                return ret;

            _sendBytes += tunnelBuffer.innerWriteBufferLength();

            _outboundTransmitList.remove(tunnelBuffer, TunnelStreamBuffer.RETRANS_LINK);
            _outboundMsgAckList.push(tunnelBuffer, TunnelStreamBuffer.RETRANS_LINK);
                                                
            error.text("");
            error.errorId(ReactorReturnCodes.SUCCESS);
		}
		return ret;
	}
	
	
	
	int sendCloseMsg(Error error)
	{
		int ret;
        TunnelStreamState tunnelStreamState = _tunnelStreamState;

		if ((ret = streamClosed(error)) != ReactorReturnCodes.SUCCESS)
		return ret;		
	    
		if (tunnelStreamState == TunnelStreamState.SEND_REQUEST || tunnelStreamState == TunnelStreamState.NOT_OPEN)
		    return ReactorReturnCodes.SUCCESS; /* Stream was not open, no need to send CloseMsg. */
		
        // send close for consumer and status for provider
        _encMsg.clear();
        _encMsg.streamId(_streamId);
        _encMsg.domainType(_domainType);
        if (!isProvider()) // consumer
        {
            _encMsg.msgClass(MsgClasses.CLOSE);
            _encMsg.containerType(DataTypes.NO_DATA);
        }
        else // provider
        {
            _encMsg.msgClass(MsgClasses.STATUS);
            _encMsg.containerType(DataTypes.NO_DATA);      
            StatusMsg statusMsg = (StatusMsg)_encMsg;
            statusMsg.applyPrivateStream();
            statusMsg.applyQualifiedStream();
            statusMsg.applyHasMsgKey();
            statusMsg.msgKey().applyHasServiceId();
            statusMsg.msgKey().serviceId(serviceId());
            statusMsg.msgKey().applyHasName();
            statusMsg.msgKey().name().data(name());
            statusMsg.applyHasState();
            statusMsg.state().streamState(StreamStates.CLOSED);
            statusMsg.state().dataState(DataStates.SUSPECT);
            statusMsg.state().code(StateCodes.NONE);
            statusMsg.state().text().data("VAProvider TunnelStream Closed");
        }

        if ((_traceFlags & TunnelStreamTraceFlags.MSGS) > 0)
            traceMsgtoXml(_traceMsg, true);

        _reactorSubmitOptions.clear();
        if  ((ret = _reactorChannel.submit(_encMsg, _reactorSubmitOptions, _errorInfo)) < ReactorReturnCodes.SUCCESS)
        {
            errorInfoToError(_errorInfo, error);
            return ret;
        }
        
        return ReactorReturnCodes.SUCCESS;
	}
	
	int encodeTunnelStreamHeaderInit(EncodeIterator eIter, int seqNum)
	{
	    int ret; 
	    
        _tunnelStreamHdr.clear();
        _tunnelStreamHdr.msgClass(MsgClasses.GENERIC);
        /* Use channel-facing stream ID (so message can go directly to the channel even if watchlist is enabled). */
        _tunnelStreamHdr.streamId(_channelStreamId);
        _tunnelStreamHdr.domainType(_domainType);
        _tunnelStreamHdr.containerType(DataTypes.MSG);
        _tunnelStreamHdr.applyHasExtendedHdr();
        _tunnelStreamHdr.applyMessageComplete();
        _tunnelStreamHdr.applyHasSeqNum();
        _tunnelStreamHdr.seqNum(seqNum);

        if ((ret = _tunnelStreamHdr.encodeInit(eIter, 0)) != CodecReturnCodes.ENCODE_EXTENDED_HEADER)
        {
            return ret;
        }
        
        if ((ret = eIter.encodeNonRWFInit(_tmpBuffer)) != CodecReturnCodes.SUCCESS)
        {
            return ret;
        }
        
        if (_tmpBuffer.length() < 1)
        {
           return ret;
        }
        
        _tmpBuffer.data().put((byte)OpCodes.DATA);

        if ((ret = eIter.encodeNonRWFComplete(_tmpBuffer, true)) != CodecReturnCodes.SUCCESS)
        {
            return ret;
        }
        
        if ((ret = _tunnelStreamHdr.encodeExtendedHeaderComplete(eIter,  true)) < CodecReturnCodes.SUCCESS)
        {
            return ret;
        }

        return ReactorReturnCodes.SUCCESS;
	}
	
	/* Print an XML comment containing the timestamp in UTC. */
	private void dumpTimestamp()
	{
		System.out.println("<!-- " + _traceDateFormat.format(Calendar.getInstance().getTime()) + " (UTC) -->");
	}

	/* Print an XML trace of a TunnelStream buffer. */
	void traceBufferToXml(TransportBuffer tBuffer)
	{
		int ret;
		assert((_traceFlags & TunnelStreamTraceFlags.MSGS) > 0);

		dumpTimestamp();
		
		_traceIter.clear();
		_traceIter.setBufferAndRWFVersion(tBuffer, _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());

		if ((ret = _traceMsg.decode(_traceIter)) != CodecReturnCodes.SUCCESS)
		{
			System.out.printf("<!-- TunnelTrace: Failed to decode message (%d) -->\n", ret);
			return;
		}

		traceMsgtoXml(_traceMsg, false);
	}

	/* Print an XML trace of a TunnelStream message. */
	void traceMsgtoXml(Msg msg, boolean timestamp)
	{
		int ret;

		assert((_traceFlags & TunnelStreamTraceFlags.MSGS) > 0);
		
		if (timestamp) dumpTimestamp();

		if (msg.msgClass() == MsgClasses.GENERIC)
		{
			/* Stream header trace. */
		    _traceIter.clear();
			if ((ret = _tunnelStreamMsg.decode(_traceIter, (GenericMsg)msg, 
							_traceAckRangeList, _traceNakRangeList)) != CodecReturnCodes.SUCCESS)
				System.out.printf("<!-- TunnelTrace: Failed to decode stream header (%d) -->\n", ret);
			else
			{
				System.out.print(_tunnelStreamMsg.xmlDumpBegin(_traceAckRangeList, _traceNakRangeList));
		
				/* Substream header trace. */
				switch(_tunnelStreamMsg.opCode())
				{
					case TunnelStreamMsg.OpCodes.DATA:
					case TunnelStreamMsg.OpCodes.RETRANS:
	
						_traceIter.clear();
						_traceIter.setBufferAndRWFVersion(msg.encodedDataBody(), _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());

						if ((ret = _traceSubMsg.decode(_traceIter)) < CodecReturnCodes.SUCCESS)
						{
							System.out.printf("<!-- TunnelTrace: Failed to decode stream header (%d) -->\n", ret);
							break;
						}
						
						QueueMsg queueSubHeader = substreamBind(_traceSubMsg);	
						if (queueSubHeader == null )
						{
							System.out.printf("<!-- TunnelTrace: Failed to decode substream header, unrecognized opCode");
							return;
						}
						if ((ret = queueSubHeader.decode(_traceIter, _traceSubMsg)) != CodecReturnCodes.SUCCESS)								
						
							System.out.printf("<!-- TunnelTrace: Failed to decode substream header (%d) -->\n", ret);
						else
							System.out.print("    " + ((QueueMsgImpl)queueSubHeader).xmlDump());
						
						break;
						
					default:
						break;
				}
				
				System.out.println(_tunnelStreamMsg.xmlDumpEnd());
				
			}
		}

		if ((_traceFlags & TunnelStreamTraceFlags.MSG_XML) > 0)
		{
			/* XML trace */
			_traceIter.clear();
			_traceIter.setBufferAndRWFVersion(msg.encodedMsgBuffer(), _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());
			System.out.println("<!-- TunnelTrace: Full message: -->");
			System.out.println(_traceSubMsg.decodeToXml(_traceIter));
		}
		else
		{
			System.out.println();
		}
	}

	int readMsg(Msg deliveredMsg, Error error)
	{
		int ret;

		try
		{    		
    		if ((_traceFlags & TunnelStreamTraceFlags.ACTIONS) > 0)
    		{
    			System.out.println("<!-- TunnelTrace: Received message, state(" 
    				+ _state.toString() + ") -->");
    		}
    
    		if ((_traceFlags & TunnelStreamTraceFlags.MSGS) > 0)
    			traceMsgtoXml(deliveredMsg, true);
    		
    		
    		if (deliveredMsg.msgClass() == MsgClasses.GENERIC)
    		{
    		    _decIter.clear();
    			ret = _tunnelStreamMsg.decode(_decIter, (GenericMsg)deliveredMsg, _recvAckRangeList, _recvNakRangeList);
    			if (ret != CodecReturnCodes.SUCCESS)
    			{
    				error.errorId(ret);
    				error.text("Failed to decode TunnelStream header");
    				return ReactorReturnCodes.FAILURE;
    			}
    		}
 
    		switch (_tunnelStreamState)
    		{
    			case NOT_OPEN:
    				return ReactorReturnCodes.SUCCESS;
    			case WAITING_REFRESH:
    			{
    				com.thomsonreuters.upa.codec.State state;    				
    				switch (deliveredMsg.msgClass())
    				{
    					case MsgClasses.REFRESH:
    						state = ((RefreshMsg)deliveredMsg).state();
    						break;
    					case MsgClasses.STATUS:
    						state = ((StatusMsg)deliveredMsg).state();
    						break;
    					default:
    						error.errorId(ReactorReturnCodes.FAILURE);
    						error.text("Received unexpected MsgClass " + deliveredMsg.msgClass()
    								+ " while establishing stream.");
    						return ReactorReturnCodes.FAILURE;
    				}
    				
    				switch(state.streamState())
    				{
    					case StreamStates.OPEN:
    						if (state.dataState() != DataStates.OK)
    							return ReactorReturnCodes.SUCCESS;
    						break;
    						
    					default:
    						error.errorId(ReactorReturnCodes.FAILURE);
    						error.text("Received non-open stream state (" + state.toString() + ")");
    						return ReactorReturnCodes.FAILURE;	
    				}
    
    				if ((_traceFlags & TunnelStreamTraceFlags.ACTIONS) > 0)
    				{
    					System.out.println("<!-- TunnelTrace: Stream "
    							+ deliveredMsg.streamId() + " established, opening substream -->");
    				}

                    _recvLastSeqNumAckSent = _recvLastSeqNum;
                    _tunnelStreamState = TunnelStreamState.STREAM_OPEN;

                    /* Remove response timer. */
                    _nextTimeoutNsec = 0;
                    _reactorChannel.tunnelStreamManager().removeTunnelStreamFromTimeoutList(this);

                    _reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
                    
    				return ReactorReturnCodes.SUCCESS;
    			}
    
                case STREAM_OPEN:
                case SEND_FIN:
                case SEND_FIN_ACK:
                case WAIT_FIN_ACK:
                case WAIT_FINAL_FIN_ACK:	
                	if (deliveredMsg.msgClass() == MsgClasses.CLOSE)
                    {                    	
                    	if (isProvider())
                    	{
                    		if (_finalStatusEvent)
                    			_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusClose(this, error);
                    	}
                    	else
                    	{
                    		if (_finalStatusEvent)
                    			_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusCloseRecover(this, error);
                    	}	
                    	_tunnelStreamState = TunnelStreamState.NOT_OPEN;
                    	_finAckWaitCount = 0;
                    	_reactorChannel.tunnelStreamManager().removeTunnelStream(this);                    	                   
                    }            
 
                    if (deliveredMsg.msgClass() != MsgClasses.GENERIC)
                    {
                        error.errorId(ReactorReturnCodes.FAILURE);
                        error.text("Unhandled message class: " + deliveredMsg.msgClass());
                        
                        return ReactorReturnCodes.FAILURE;
                    }
    
    				switch(_tunnelStreamMsg.opCode())
    				{
                        case TunnelStreamMsg.OpCodes.DATA:
                        case TunnelStreamMsg.OpCodes.RETRANS:
                            TunnelSubstream substreamSession = null;
                            TunnelStreamMsg.TunnelStreamData dataHeader = (TunnelStreamMsg.TunnelStreamData)_tunnelStreamMsg; 
                            int seqCompare;
    
                            seqCompare = TunnelStreamUtil.seqNumCompare(
                                    dataHeader.seqNum(),
                                    _recvLastSeqNum);    
                            
                            if (seqCompare == 1)
                            {
                                /* This is the next message in sequence. Process it. */
                                _recvLastSeqNum = dataHeader.seqNum();
                                _reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
                            }
                            else if (seqCompare <= 0)
                            {
                                if ((_traceFlags & TunnelStreamTraceFlags.ACTIONS) > 0)
                                    System.out.println("<!-- TunnelTrace: Discarding duplicate/old message (SeqNum: " 
                                            + dataHeader.seqNum() + " vs. expected " + (_recvLastSeqNum + 1) + "-->");
    
                                /* Old/duplicate data. Ignore it. */
                                error.errorId(CodecReturnCodes.SUCCESS);
                                return ReactorReturnCodes.SUCCESS;
                            }
                            else
                            {
                                if ((_traceFlags & TunnelStreamTraceFlags.ACTIONS) > 0)
                                	System.out.println("<!-- TunnelTrace: Message indicates gap (SeqNum: " 
                                            + dataHeader.seqNum() + " vs. expected " + (_recvLastSeqNum + 1) + "-->");
                                /* Request retransmission of lost messages. */
                                _sendNakRangeList.rangeArray()[0] = _recvLastSeqNum + 1;
                                if (dataHeader.seqNum() > _sendLastSeqNumNaked)
                                {
                                    _sendLastSeqNumNaked = dataHeader.seqNum();
                                }
                                _sendNakRangeList.rangeArray()[1] = _sendLastSeqNumNaked;
                                _sendNakRangeList.count(1);
                                _reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
                                error.errorId(CodecReturnCodes.SUCCESS);
                                return ReactorReturnCodes.SUCCESS;
                            }
    
                            _decSubMsg.clear();
                            if (deliveredMsg.containerType() == DataTypes.MSG)
                            {
                                _decIter.clear();
                                _decIter.setBufferAndRWFVersion(deliveredMsg.encodedDataBody(), _classOfService.common().protocolMajorVersion(), _classOfService.common().protocolMinorVersion());
                                if ((ret = _decSubMsg.decode(_decIter)) < CodecReturnCodes.SUCCESS)
                                {
                                    error.errorId(ret);
                                    error.text("Failed to decode stream header (" + ret + ")");
                                    return ReactorReturnCodes.FAILURE;
                                }
                                substreamSession = _streamIdtoQueueSubstreamTable.get(_decSubMsg.streamId());
                            }
    
                            if (substreamSession != null) // queue message
                            {
                                return substreamSession.readMsg(deliveredMsg, error);
                            }
                            else
                            {
                                if (!isProvider()) // consumer tunnel stream
                                {
                                    // if authentication enabled, check for login refresh
                                    if (_classOfService.authentication().type() == ClassesOfService.AuthenticationTypes.OMM_LOGIN &&
                                        _decSubMsg.domainType() == DomainTypes.LOGIN)
                                    {
                                        if (_decSubMsg.msgClass() == MsgClasses.REFRESH) // login refresh
                                        {
                                            // send TunnelStream status event via Reactor
                                            _loginMsg.clear();
                                            _loginMsg.rdmMsgType(LoginMsgType.REFRESH);
                                            _loginMsg.decode(_decIter, _decSubMsg);
                                            _reactorChannel.tunnelStreamManager().sendTunnelStreamStatus(this, ((RefreshMsg)_decSubMsg).state(), _decSubMsg, _loginMsg);
                                            
                                            return ReactorReturnCodes.SUCCESS;
                                        }
                                        else if (_decSubMsg.msgClass() == MsgClasses.STATUS)
                                        {
                                            com.thomsonreuters.upa.codec.State state = null;
                                            if (((StatusMsg)_decSubMsg).checkHasState())
                                            {
                                                state = ((StatusMsg)_decSubMsg).state();
                                            }
                                            
                                            // send TunnelStream status event via Reactor
                                            _loginMsg.clear();
                                            _loginMsg.rdmMsgType(LoginMsgType.STATUS);
                                            _loginMsg.decode(_decIter, _decSubMsg);
                                            _reactorChannel.tunnelStreamManager().sendTunnelStreamStatus(this, state, _decSubMsg, _loginMsg);
                                            
                                            // if Login stream is closed, send close message for TunnelStream
                                            if (state != null &&
                                                (state.streamState() == StreamStates.CLOSED ||
                                                state.streamState() == StreamStates.CLOSED_RECOVER))
                                            {
                                                sendCloseMsg(error);
                                            }
                                        }
                                    }
                                    else // not a login
                                    {
                                        TunnelStreamBuffer buffer = getBuffer(deliveredMsg.encodedDataBody().length(), false, false, error);
                                        if (buffer != null)
                                        {
                                            deliveredMsg.encodedDataBody().copy(buffer.data());
                                            msgReceived(buffer, (deliveredMsg.containerType() == DataTypes.MSG) ? _decSubMsg : null, deliveredMsg.containerType());
                                            releaseBuffer((TunnelStreamBuffer)buffer, error);
                
                                            return ReactorReturnCodes.SUCCESS;
                                        }
                                        else
                                        {
                                            return error.errorId();
                                        }
                                    }
                                }
                                else // provider tunnel stream
                                {
                                    TunnelStreamBuffer buffer = getBuffer(deliveredMsg.encodedDataBody().length(), false, false, error);
                                    if (buffer != null)
                                    {
                                        deliveredMsg.encodedDataBody().copy(buffer.data());
                                        msgReceived(buffer,  (deliveredMsg.containerType() == DataTypes.MSG) ? _decSubMsg : null, deliveredMsg.containerType());
                                        releaseBuffer((TunnelStreamBuffer)buffer, error);
            
                                        return ReactorReturnCodes.SUCCESS;
                                    }
                                    else
                                    {
                                        return error.errorId();
                                    }
                                }
                            }
    
    					case TunnelStreamMsg.OpCodes.ACK:
    						if ((ret = processAck((TunnelStreamMsg.TunnelStreamAck)_tunnelStreamMsg, _recvAckRangeList, _recvNakRangeList, error)) != ReactorReturnCodes.SUCCESS)
    							return ret;
    				
    	                    if (_outboundTransmitList.peek() != null)
    	                        _reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
    
    						error.errorId(ReactorReturnCodes.SUCCESS);
    						return ReactorReturnCodes.SUCCESS;
    
    					default:
    						error.errorId(ReactorReturnCodes.FAILURE);
    						error.text("Received unhandled TunnelStream header op code " + _tunnelStreamMsg.opCode()
    								+ " while establishing substream.");
    						return ReactorReturnCodes.FAILURE;
    				}
                case CLOSING:
                   	if (deliveredMsg.msgClass() == MsgClasses.CLOSE)
                    {                    	
                    	if (isProvider())
                    	{
                    		if (_finalStatusEvent)
                    			_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusClose(this, error);
                    	}
                    	else
                    	{
                    		if (_finalStatusEvent)
                    			_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusCloseRecover(this, error);
                    	}	
                    	_tunnelStreamState = TunnelStreamState.NOT_OPEN;
                    	_finAckWaitCount = 0;                    

                        _reactorChannel.tunnelStreamManager().removeTunnelStream(this);                    	                   
                    }        
                   	return ReactorReturnCodes.SUCCESS;
     
    			default:
    				error.errorId(ReactorReturnCodes.FAILURE);
    				error.text("Unknown TunnelStream state.");
    				return ReactorReturnCodes.FAILURE;
    
    		}
        }
        catch (Exception e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;
        }
        catch (InternalError e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;          
        }
	}
	
	int processAck(TunnelStreamMsg.TunnelStreamAck ackHeader, AckRangeList ackRangeList, AckRangeList nakRangeList, Error error)
	{
	    int ret;
		TunnelStreamBuffer buffer;
			
		// FIN 
		try
		{					
			if ((ackHeader.flag() & _ackOpcodeFin) > 0) // FIN received by our application
			{
				int seqCompare = TunnelStreamUtil.seqNumCompare(
                    ackHeader.seqNum(),
                    _recvLastSeqNum);
                       
				if (seqCompare == 1)
				{
					/* This is the next message in sequence. Process it. */
					_recvLastSeqNum = ackHeader.seqNum();
				}			
                _classOfService.flowControl().sendWindowSize(ackHeader.recvWindow());

				if (_tunnelStreamState == TunnelStreamState.WAIT_FIN_ACK) 
				{   
					_receivedFinalFin = true;

					if (TunnelStreamUtil.seqNumCompare(ackHeader.seqNum(), _receivedFinalFinSeqNum) > 0)
					{
						_receivedFinalFinSeqNum =  ackHeader.seqNum();
						if (_receivedFinAck && _receivedFinalFin)
						{

					        for (Integer substreamId : _streamIdtoQueueSubstreamTable.keySet())
					        {
					            if ((ret = closeSubstream(substreamId.intValue(), error)) != ReactorReturnCodes.SUCCESS)
					                return ret;
					        }
					        _streamIdtoQueueSubstreamTable.clear();
							_tunnelStreamState = TunnelStreamState.SEND_FINAL_FIN_ACK_AND_CLOSING;			    					
						}
					}
				}
				else if (_tunnelStreamState ==  TunnelStreamState.STREAM_OPEN ||
					_tunnelStreamState ==  TunnelStreamState.SEND_REQUEST ||
							_tunnelStreamState ==  TunnelStreamState.WAITING_REFRESH 
							|| _tunnelStreamState ==  TunnelStreamState.WAIT_FINAL_FIN_ACK) 				
				{						
					_finAckWaitCount = 0;
					_sendFinSeqNum = -1;
					_hasFinSent = false;
					if (TunnelStreamUtil.seqNumCompare(ackHeader.seqNum(), _receivedLastFinSeqNum) > 0)
					{
						_receivedLastFinSeqNum =  ackHeader.seqNum();
						
						_tunnelStreamState = TunnelStreamState.SEND_FIN_ACK;  
						if (_finalStatusEvent)
							_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusPendingClose(this, error);
					}
				}    	
				else
				{
					System.out.println("Unkown FIN/ACK flag ");
					return TransportReturnCodes.SUCCESS;
				}			
				_reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);					
			}		
			else 
			{
                _classOfService.flowControl().sendWindowSize(ackHeader.recvWindow());
				if (TunnelStreamUtil.seqNumCompare(ackHeader.seqNum(), _sendLastSeqNumAcked) > 0)
				{
					_sendLastSeqNumAcked = ackHeader.seqNum();
 
					if (_sendLastSeqNumAcked == _sendFinSeqNum) // our application sent FIN and we received ACK for that FIN
					{							
						_receivedFinAck = true;
					}   			
     				
					if (_receivedFinAck && _receivedFinalFin)
					{    					    					
						_tunnelStreamState = TunnelStreamState.SEND_FINAL_FIN_ACK_AND_CLOSING;

				        for (Integer substreamId : _streamIdtoQueueSubstreamTable.keySet())
				        {
				            if ((ret = closeSubstream(substreamId.intValue(), error)) != ReactorReturnCodes.SUCCESS)
				                return ret;
				        }
				        _streamIdtoQueueSubstreamTable.clear();
    				
						_reactorChannel.tunnelStreamManager().addTunnelStreamToDispatchList(this);
					}    		
					if( _tunnelStreamState == TunnelStreamState.WAIT_FINAL_FIN_ACK)
					{
						_finAckWaitCount = 0;
						_tunnelStreamState = TunnelStreamState.CLOSING;
					}   	  				
				}
				
				if (TunnelStreamUtil.seqNumCompare(_sendLastSeqNumAcked, _sendLastSeqNum) > 0)
				{
						/* Error: Acked seqNum was ahead of our last sent. 
						 * Not sure how likely this is. */
					_sendLastSeqNumAcked = _sendLastSeqNum;
				}
    
				/* Acknowledge messages up to the cumulative sequence number. */
				while((buffer = _outboundMsgAckList.peek()) != null
						&& (TunnelStreamUtil.seqNumCompare(buffer.seqNum(), _sendLastSeqNumAcked) <= 0))
					freeAckedWriteBuffer(buffer);
						
    
				/* Now, acknowledge messages in selective acks */
				if (ackRangeList.count() > 0)
				{
						int[] ackRanges = ackRangeList.rangeArray();
						buffer = _outboundMsgAckList.start(TunnelStreamBuffer.RETRANS_LINK);
    
						for(int i = 0; i < ackRangeList.count() * 2; i += 2)
						{
						/* Remove each buffer within the ack range.
						 * The buffers should be in order in our list, as should the
						 * ack ranges, so skip any that aren't in the current range. */
							while (buffer != null && buffer.seqNum() <= ackRanges[i + 1])
							{
								if (buffer.seqNum() >= ackRanges[i])
									freeAckedWriteBuffer(buffer);
    
								buffer = _outboundMsgAckList.forth(TunnelStreamBuffer.RETRANS_LINK);
							}
						}
				}
    
                /* Now, identify messages for retransmission in naks */
				if (nakRangeList.count() > 0)
				{
						int[] nakRanges = nakRangeList.rangeArray();
						buffer = _outboundMsgAckList.start(TunnelStreamBuffer.RETRANS_LINK);
    
						for(int i = 0; i < nakRangeList.count() * 2; i += 2)
						{
							/* Remove each buffer within the ack range.
							 * The buffers should be in order in our list, as should the
							 * ack ranges, so skip any that aren't in the current range. */
							while (buffer != null && buffer.seqNum() <= nakRanges[i + 1])
							{
								if (buffer.seqNum() >= nakRanges[i])
								{
                                    _sendBytes -= buffer.innerWriteBufferLength();
                                    buffer.isRetransmit(true);
                                    _outboundMsgAckList.remove(buffer, TunnelStreamBuffer.RETRANS_LINK);
                                    _outboundTransmitList.push(buffer, TunnelStreamBuffer.RETRANS_LINK);
								}
								else // close stream if retransmission gap is non-recoverable
								{
									_reactorChannel.tunnelStreamManager().sendTunnelStreamStatusCloseRecover(this, error);
								}
    
								buffer = _outboundMsgAckList.forth(TunnelStreamBuffer.RETRANS_LINK);
							}
						}
				}

                if (isSendWindowOpen(null) && _outboundTransmitList.peek() != null)
                    tunnelStreamManager().addTunnelStreamToDispatchList(this);
			}
		}
		catch (Exception e)
		{	
			error.errorId(ReactorReturnCodes.FAILURE);
			error.text(e.getLocalizedMessage());
			return ReactorReturnCodes.FAILURE;
		}
        catch (InternalError e)
        {
            error.errorId(ReactorReturnCodes.FAILURE);
            error.text(e.getLocalizedMessage());
            return ReactorReturnCodes.FAILURE;          
        }
		        
		return ReactorReturnCodes.SUCCESS;
	}

	/* Free an acknowledged write buffer. */
	void freeAckedWriteBuffer(TunnelStreamBuffer buffer)
	{
        _sendBytes -= buffer.innerWriteBufferLength();

		if (buffer.isApplicationBuffer())
			--_outboundUnackedAppMsgCount;

		_outboundMsgAckList.remove(buffer, TunnelStreamBuffer.RETRANS_LINK);

		releaseBuffer(buffer, _errorInfo.error());
	}

	/* Determine if the recvWindow has room for sending data
	 * (or, if no buffer is given, whether a message might be possible to send). */
	boolean isSendWindowOpen(TunnelStreamBuffer buffer)
	{
	    boolean retVal = true;
	    
	    if (_classOfService.flowControl().type() == ClassesOfService.FlowControlTypes.BIDIRECTIONAL)
	    {
	        if (_firstIsSendWindowOpenCall)
	        {
	            _firstIsSendWindowOpenCall = false;
	            
	            // always allow initial login
	            if (_classOfService.authentication().type() == ClassesOfService.AuthenticationTypes.OMM_LOGIN)
	            {
	                return retVal;
	            }
	        }

            return _sendBytes + (buffer != null ? buffer.innerWriteBufferLength() : 0)<= _classOfService.flowControl().sendWindowSize();
	    }
		
		return retVal;
	}

    TransportBuffer getChannelBuffer(int length, boolean packedBuffer, Error error)
    {
        assert(_reactorChannel.tunnelStreamManager().reactorChannel().channel() != null);

        Channel channel = _reactorChannel.tunnelStreamManager().reactorChannel().channel();
        TransportBuffer tBuffer = null;

        while ((tBuffer = channel.getBuffer(length, packedBuffer, error)) == null)
        {
            if (error.errorId() != ReactorReturnCodes.NO_BUFFERS)
            {
                error.errorId(ReactorReturnCodes.CHANNEL_ERROR);
                return null;
            }
            else // out of buffers
            {
                // increase buffers
                _chnlInfo.clear();
                if (channel.info(_chnlInfo, error) < ReactorReturnCodes.SUCCESS)
                {
                    error.errorId(ReactorReturnCodes.CHANNEL_ERROR);
                    return null;
                }
                int newNumberOfBuffers = _chnlInfo.guaranteedOutputBuffers() + 10;
                if (channel.ioctl(IoctlCodes.NUM_GUARANTEED_BUFFERS, newNumberOfBuffers, error) < ReactorReturnCodes.SUCCESS)
                {
                    error.errorId(ReactorReturnCodes.CHANNEL_ERROR);
                    return null;
                }
            }
        }
        
        return tBuffer;
    }
	
	int releaseChannelBuffer(TransportBuffer buffer, Error error)
	{
        assert(_reactorChannel.tunnelStreamManager().reactorChannel().channel() != null);

        Channel channel = _reactorChannel.tunnelStreamManager().reactorChannel().channel();
        
        return channel.releaseBuffer(buffer, error);
	}

	int writeChannelBuffer(TransportBuffer tBuffer, Error error)
	{
		int ret;
		assert(_reactorChannel.tunnelStreamManager().reactorChannel().channel() != null);
		
		Channel channel = _reactorChannel.tunnelStreamManager().reactorChannel().channel();
		
        if (xmlTracing() == true)
        {
            _xmlIter.clear();
            _xmlIter.setBufferAndRWFVersion(tBuffer,
                                            reactorChannel().channel().majorVersion(),
                                            reactorChannel().channel().minorVersion());
            System.out.print("Outgoing Reactor message (" + new java.util.Date() + "):");
            System.out.println(_xmlMsg.decodeToXml(_xmlIter, null));
        }

		_writeArgs.clear();
		ret = channel.write(tBuffer, _writeArgs, error);
		
		if (ret > 0)
		{
			_reactorChannel.tunnelStreamManager().setNeedsFlush();
			_writeCallAgainBuffer = null;
			return ReactorReturnCodes.SUCCESS;
		}
		else
		{
			switch(ret)
			{
				case TransportReturnCodes.SUCCESS:
				    _writeCallAgainBuffer = null;
					return ReactorReturnCodes.SUCCESS;

				case TransportReturnCodes.WRITE_CALL_AGAIN:
				    _writeCallAgainBuffer = tBuffer;
	                _reactorChannel.tunnelStreamManager().setNeedsFlush();
				    return ReactorReturnCodes.SUCCESS;

				default:
					channel.releaseBuffer(tBuffer, error);
					return ReactorReturnCodes.CHANNEL_ERROR;
			}
		}
	}

	int enableTrace(int traceFlags)
	{
		_traceFlags = traceFlags;
		return ReactorReturnCodes.SUCCESS;
	}

	int getStateInfo(TunnelStreamStateInfo info)
	{
	    try
	    {
    		info.set(_tunnelStreamState,
              _outboundTransmitList.count(),
              _outboundMsgAckList.count(),
              _classOfService.flowControl().sendWindowSize() - _sendBytes,
              _classOfService.flowControl().recvWindowSize() - _recvBytes);
        }
        catch (Exception e)
        {
            return ReactorReturnCodes.FAILURE;
        }
        catch (InternalError e)
        {
            return ReactorReturnCodes.FAILURE;          
        }
	    
		return ReactorReturnCodes.SUCCESS;
	}
	
    int handleTimer(long currentTimeNsec, Error error)
    {
        switch(_tunnelStreamState)
        {
            case WAITING_REFRESH:
                if (System.nanoTime() > _nextTimeoutNsec)
                {
                    /* Timed out waiting for provider response. */
                    error.text("Open TunnelStream timeout has occurred");
                    _reactorChannel.tunnelStreamManager().sendTunnelStreamStatusCloseRecover(this, error);
                    return ReactorReturnCodes.SUCCESS;
                }
                break;
                
            default:
                try
                {
                    expireTimeoutMessages(currentTimeNsec, error);
                }
                catch (Exception e)
                {
                    error.errorId(ReactorReturnCodes.FAILURE);
                    error.text(e.getLocalizedMessage());
                    return ReactorReturnCodes.FAILURE;
                }
                catch (InternalError e)
                {
                    error.errorId(ReactorReturnCodes.FAILURE);
                    error.text(e.getLocalizedMessage());
                    return ReactorReturnCodes.FAILURE;          
                }

                break;

        }
        return ReactorReturnCodes.SUCCESS;
    }

    /* Insert buffer in order into timeout list */
    void insertTimeoutBuffer(TunnelStreamBuffer tunnelBuffer, long currentTimeNsec)
    {
        assert(tunnelBuffer.timeoutNsec() > 0);

        long timeout = tunnelBuffer.timeoutNsec();
        TunnelStreamBuffer tmpBuffer;

        /* Starting with the latest timeout, find a message (if any) with
         * a sooner timeout than the new buffer */
        for(tmpBuffer = _outboundTimeoutList.peekTail();
                tmpBuffer != null && (timeout - tmpBuffer.timeoutNsec() <= 0);
                tmpBuffer = TunnelStreamBuffer.TIMEOUT_LINK.getPrev(tmpBuffer))
            ;

        if (tmpBuffer != null)
            _outboundTimeoutList.insertAfter(tmpBuffer, tunnelBuffer, TunnelStreamBuffer.TIMEOUT_LINK);
        else
            _outboundTimeoutList.pushBack(tunnelBuffer, TunnelStreamBuffer.TIMEOUT_LINK);

        tmpBuffer = _outboundTimeoutList.peek();
        if (tmpBuffer.timeoutNsec() - currentTimeNsec > 0)
            _nextTimeoutNsec = tmpBuffer.timeoutNsec();
        else
            _nextTimeoutNsec = currentTimeNsec;

        updateTimeout(currentTimeNsec);
    }

    /* Expire any timed-out buffers (e.g. QueueData messages). */
    void expireTimeoutMessages(long currentTimeNsec, Error error)
    {
        TunnelStreamBuffer tunnelBuffer;

        for(tunnelBuffer = _outboundTimeoutList.start(TunnelStreamBuffer.TIMEOUT_LINK); 
                tunnelBuffer != null && tunnelBuffer.timeoutNsec() - currentTimeNsec < 0; 
                tunnelBuffer = _outboundTimeoutList.forth(TunnelStreamBuffer.TIMEOUT_LINK))
        {
            assert(tunnelBuffer.isQueueData());
            _outboundTimeoutList.remove(tunnelBuffer, TunnelStreamBuffer.TIMEOUT_LINK);
            queueMsgExpired(tunnelBuffer, null, QueueDataUndeliverableCode.EXPIRED);
            if (tunnelBuffer.persistenceBuffer() != null)
                tunnelBuffer.tunnelSubstream().releasePersistenceBuffer(tunnelBuffer.persistenceBuffer());
            _outboundTransmitList.remove(tunnelBuffer, TunnelStreamBuffer.RETRANS_LINK);
            releaseBuffer(tunnelBuffer, error);
        }

        updateTimeout(currentTimeNsec);
    }

    /* Expire any messages with an IMMEDIATE timeout. Done in response to a queue stream
     * being closed before these messages were transmitted. */
    void expireImmediateMessages(Error error)
    {
        TunnelStreamBuffer tunnelBuffer;

        while((tunnelBuffer = _outboundImmediateList.pop(TunnelStreamBuffer.TIMEOUT_LINK)) != null)
        {
            queueMsgExpired(tunnelBuffer, null,  QueueDataUndeliverableCode.EXPIRED);
            if (tunnelBuffer.persistenceBuffer() != null)
                tunnelBuffer.tunnelSubstream().releasePersistenceBuffer(tunnelBuffer.persistenceBuffer());
            _outboundTransmitList.remove(tunnelBuffer, TunnelStreamBuffer.RETRANS_LINK);
            releaseBuffer(tunnelBuffer, error);
        }
    }

    /* Set tunnelstream timeout to match the next timeout event. */
    void updateTimeout(long currentTimeNsec)
    {
        TunnelStreamBuffer tunnelBuffer = _outboundTimeoutList.peek();

        if (tunnelBuffer != null)
        {
            _nextTimeoutNsec = tunnelBuffer.timeoutNsec();
            tunnelStreamManager().addTunnelStreamToTimeoutList(this, _nextTimeoutNsec);
        }
        else
            tunnelStreamManager().removeTunnelStreamFromTimeoutList(this);
    }

	int traceFlags() { return _traceFlags; }

	long nextTimeoutNsec() { return _nextTimeoutNsec; }

	boolean hasNextTimeout() { return _hasNextTimeout; }

	void hasNextTimeout(boolean hasNextTimeout) { _hasNextTimeout = hasNextTimeout; }

	TunnelStreamState tunnelStreamState() { return _tunnelStreamState; }

    QueueMsg substreamBind(Msg tmpSubMsg)
    {	
    	switch (tmpSubMsg.msgClass())
    	{	
			case MsgClasses.GENERIC:
				GenericMsg genericMsg = (GenericMsg)tmpSubMsg;
				ByteBuffer tmpByteBuf;
				int tmpPos, tmpLimit;
				tmpByteBuf = genericMsg.extendedHeader().data();
				tmpPos = tmpByteBuf.position();
				tmpLimit = tmpByteBuf.limit();

				tmpByteBuf.position(genericMsg.extendedHeader().position());
				int opCode = tmpByteBuf.get();
				
		   		tmpByteBuf.limit(tmpLimit);
	    		tmpByteBuf.position(tmpPos);

				switch(opCode)
				{			
					case QueueMsgImpl.OpCodes.DATA:
						return _queueData;
					case QueueMsgImpl.OpCodes.DEAD_LETTER:
						return _queueDataExpired;
					case QueueMsgImpl.OpCodes.ACK:
						return _queueAck;
					default:
						return null;
				}
			case MsgClasses.REQUEST:
				return _queueRequest;
			case MsgClasses.REFRESH:
				return _queueRefresh;
			case MsgClasses.CLOSE:
				return _queueClose;
			case MsgClasses.STATUS:
				return _queueStatus;
			default:						
				return null;
    	}  	    
	}    

    /* Sets the channel-facing stream ID. This may be used with messages that are
     * safe to send directly to the channel (namely GenericMsgs for TunnelStream data and acking). */
    void channelStreamId(int channelStreamId)
    {
        _channelStreamId = channelStreamId;
    }

    /* Copy ReactorErrorInfo information to transport Error. */
    private void errorInfoToError(ReactorErrorInfo errorInfo, Error error)
    {
        error.clear();
        error.channel(errorInfo.error().channel());
        error.errorId(errorInfo.error().errorId());
        error.sysError(errorInfo.error().sysError());
        if (errorInfo.error().text() != null)
            error.text(errorInfo.error().text());
    }
    
    void tableKey(WlInteger tableKey)
    {
        _tableKey = tableKey;
    }
    
    WlInteger tableKey()
    {
        return _tableKey;
    }
}
