package ch.epfl.gsn.wrappers.backlog.sf;

import ch.epfl.gsn.wrappers.backlog.BackLogMessage;
import ch.epfl.gsn.wrappers.backlog.BackLogMessageListener;
import ch.epfl.gsn.wrappers.backlog.BackLogMessageMultiplexer;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Iterator;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import net.tinyos.packet.SFProtocol;
import net.tinyos.util.Messenger;


/**
 * This class implements the serial forwarder (v2) client functionality.
 * <p>
 * It listens for incoming messages and forwards them to the
 * {@link gsn.wrappers.BackLogWrapper BackLogWrapper} which sends them to
 * the deployment.
 * Furthermore, it registers itself to the
 * {@link gsn.wrappers.backlog.DeploymentClient DeploymentClient} as listener
 * and forwards all incoming TOS packets from the deployment to the remote
 * sf client.
 *
 * @author	Tonio Gsell
 */
public class SFClient extends SFProtocol implements Runnable, BackLogMessageListener, Messenger {
	
	private static final int SF_MESSAGE_PRIORITY = 20;
	
    private Thread thread;
    private Socket socket = null;
    private SFListen listenServer;
    
    private static int sfClientThreadCounter = 1;
	
	private final transient Logger logger = LoggerFactory.getLogger( SFClient.class );

    public SFClient(Socket socket, SFListen listenSvr) throws IOException {
    	super("");
		thread = new Thread(this);
        listenServer = listenSvr;
        this.socket = socket;
        InetAddress addr = socket.getInetAddress();
        name = "client at " + addr.getHostName() + " (" + addr.getHostAddress() + ")";
        logger.info("new " + name);
        thread.setName("SFClient-Thread:" + sfClientThreadCounter++);
    }

    protected void openSource() throws IOException {
        is = socket.getInputStream();
        os = socket.getOutputStream();
        super.openSource();
    }
 
    protected void closeSource() throws IOException {
        socket.close();
    }

    private void init() throws IOException {
    	open(this);
    	Iterator<BackLogMessageMultiplexer> sources = listenServer.getSources().iterator();
    	while(sources.hasNext())
    		sources.next().registerListener(BackLogMessage.TOS_MESSAGE_TYPE, this, false);
    }

    public void shutdown() {
		try {
		    close();
		}
		catch (IOException e) { }
		sfClientThreadCounter--;
    }

    public void start() {
    	thread.start();
    }

    public final void join(long millis) throws InterruptedException {
    	thread.join(millis);
    }

    public void run() {
		try {
		    init();
		    readPackets();
		}
		catch (IOException e) { }
		finally {
	    	Iterator<BackLogMessageMultiplexer> sources = listenServer.getSources().iterator();
	    	while(sources.hasNext())
	    		sources.next().deregisterListener(BackLogMessage.TOS_MESSAGE_TYPE, this, false);
		    listenServer.removeSFClient(this);
		    shutdown();
		}
    }
	
    private void readPackets() throws IOException {
		for (;;) {
			// TODO: register this listener to newly started MigMessageMultiplexer
			// (till now only registering in init, thus, no new MigMessageMultiplexers
			// are considered...)
			byte[] packet = readPacket();
		    BackLogMessage msg;
			try {
				Serializable [] data = {packet};
				msg = new BackLogMessage(BackLogMessage.TOS_MESSAGE_TYPE, 0, data);
				
				// TODO: to which DeviceId has the message to be sent to?
			    if (!((BackLogMessageMultiplexer) listenServer.getSources().toArray()[0]).sendMessage(msg, null, SF_MESSAGE_PRIORITY))
			    	logger.error("write failed");
			    else {
					if (logger.isDebugEnabled())
						logger.debug("Message from SF with address >" + socket.getInetAddress().getHostName() + "< received and forwarded");
			    }
			} catch (Exception e) {
				logger.error(e.getMessage());
			}
        }
    }

	@Override
	public boolean messageRecv(int deviceId, BackLogMessage message) {
		try {
		    if(writeSourcePacket((byte[]) message.getPayload()[0])) {
				if (logger.isDebugEnabled())
					logger.debug("Message with timestamp " + message.getTimestamp() + " successfully written to sf client " + socket.getInetAddress().getHostName());
		    }
			else
		    	logger.error("Message with timestamp " + message.getTimestamp() + " could not be written to sf client " + socket.getInetAddress().getHostName());
		}
		catch (IOException e) {
		    shutdown();
		}
		return false;
	}

	public void message(String s) {
	}

	@Override
	public void remoteConnEstablished(Integer devicID) {
	}

	@Override
	public void remoteConnLost() {
	}
}
