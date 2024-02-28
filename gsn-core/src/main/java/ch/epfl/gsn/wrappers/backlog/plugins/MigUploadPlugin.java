package ch.epfl.gsn.wrappers.backlog.plugins;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import net.tinyos.message.SerialPacket;
import net.tinyos.packet.Serial;
import net.tinyos1x.message.TOSMsg;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import ch.epfl.gsn.beans.DataField;
import ch.epfl.gsn.beans.InputInfo;
import ch.epfl.gsn.wrappers.BackLogWrapper;


/**
 * This plugin offers the functionality to upload TOS messages to a
 * deployment. It supports TinyOS1.x as well as TinyOS2.x messages.
 * <p>
 * If the TINYOS1X_PLATFORM_NAME (default 'tinyos1x-platformName' is
 * specified in the virtual sensor's XML file, TinyOS1.x messages
 * will be generated otherwise TinyOS2.x messages.
 * 
 * @author Tonio Gsell
 */
public class MigUploadPlugin extends AbstractPlugin {
	
	private int commands_sent = 0;
	
	private final static int tinyos1x_groupId = -1;
	private String tinyos1x_platform = null;

	private TOSMsg template ;

	private final transient Logger logger = LoggerFactory.getLogger( MigUploadPlugin.class );


	@Override
	public boolean initialize(BackLogWrapper backlogwrapper, String coreStationName, String deploymentName) {
		activeBackLogWrapper = backlogwrapper;
		String p = getActiveAddressBean().getPredicateValue("priority");
		if (p == null)
			priority = null;
		else
			priority = Integer.valueOf(p);
		tinyos1x_platform = getActiveAddressBean().getPredicateValue(MigMessageMultiplexer.TINYOS1X_PLATFORM);

		// a template message for this platform has to be instantiated to be able to get the data offset
		// if a message has to be sent to the deployment
	   	Class<?> msgCls;
		try {
			msgCls = Class.forName ( "net.tinyos1x.message." + tinyos1x_platform + ".TOSMsg" );
		   	Constructor<?> c = msgCls.getConstructor () ;
			template = (TOSMsg) c.newInstance () ;
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return false;
		}
		
		registerListener();
		
		return true;
	}

	@Override
	public short getMessageType() {
		return ch.epfl.gsn.wrappers.backlog.BackLogMessage.TOS_MESSAGE_TYPE;
	}


	@Override
	public String getPluginName() {
		return "MigUploadPlugin";
	}

	@Override
	public DataField[] getOutputFormat() {
		DataField[] dataField = {new DataField("COMMANDS_SENT", "INTEGER")};
		return dataField;
	}

	@Override
	public boolean messageReceived(int deviceID, long timestamp, Serializable[] data) {
		return true;
	}

	@Override
	public InputInfo sendToPlugin(String action, String[] paramNames, Object[] paramValues) {
		boolean ret = false;
		if (logger.isDebugEnabled())
			logger.debug("action: " + action);
		if( action.compareToIgnoreCase("payload") == 0 ) {
			int moteId = -257;
			int amType = -257;
			byte[] data = null;
			
			if( paramNames.length != 3 ) {
				logger.error("upload action must have three parameter names: 'moteid', 'amtype' and 'data'");
				return new InputInfo(getActiveAddressBean().toString(), "upload action must have three parameter names: 'moteid', 'amtype' and 'data'", false);
			}
			if( paramValues.length != 3 ) {
				logger.error("upload action must have three parameter values");
				return new InputInfo(getActiveAddressBean().toString(), "upload action must have three parameter values", false);
			}
			
			for( int i=0; i<3; i++ ) {
				try {
					String tmp = paramNames[i];
					if( tmp.compareToIgnoreCase("mote id") == 0 )
						moteId = Integer.parseInt((String) paramValues[i]);
					else if( tmp.compareToIgnoreCase("am type") == 0 )
						amType = Integer.parseInt((String) paramValues[i]);
					else if( tmp.compareToIgnoreCase("payload") == 0 )
						data = ((String) paramValues[i]).getBytes();
				} catch(Exception e) {
					logger.error("Could not interprete upload arguments: " + e.getMessage());
					return new InputInfo(getActiveAddressBean().toString(), "Could not interprete upload arguments: " + e.getMessage(), false);
				}
			}
			
			if( moteId < -256 | amType < -256 | data == null ) {
				logger.error("upload action must contain all three parameter names: 'mote id', 'am type' and 'payload'");
				return new InputInfo(getActiveAddressBean().toString(), "upload action must contain all three parameter names: 'mote id', 'am type' and 'payload'", false);
			}
			
			if(data.length == 0) {
				logger.warn("Upload message's payload is empty");
			}
			
			try {
				ret = sendRemote(System.currentTimeMillis(), new Serializable[] {createTOSpacket(moteId, amType, data)}, super.priority);
				if (logger.isDebugEnabled())
					logger.debug("Mig message sent to mote id " + moteId + " with AM type " + amType);
			} catch (IOException e) {
				logger.warn(e.getMessage());
				return new InputInfo(getActiveAddressBean().toString(), e.getMessage(), false);
			}
		}
		else if( action.compareToIgnoreCase("binary_packet") == 0 ) {
			if(((String)paramNames[0]).compareToIgnoreCase("binary packet") == 0) {
				byte [] packet = ((String) paramValues[0]).getBytes();
				if(packet.length > 0) {
					try {
						ret = sendRemote(System.currentTimeMillis(), new Serializable[] {packet}, super.priority);
					} catch (IOException e) {
						logger.warn(e.getMessage());
						return new InputInfo(getActiveAddressBean().toString(), e.getMessage(), false);
					}
					if (logger.isDebugEnabled())
						logger.debug("Mig binary message sent with length " + ((String) paramValues[0]).length());
				}
				else {
					logger.error("Upload failed due to empty 'binary packet' field");
					return new InputInfo(getActiveAddressBean().toString(), "Upload failed due to empty 'binary packet' field", false);
				}
			}
			else {
				logger.error("binary_packet upload action needs a 'binary packet' field.");
				return new InputInfo(getActiveAddressBean().toString(), "binary_packet upload action needs a 'binary packet' field.", false);
			}
		}
		else
			logger.error("Unknown action");

		Serializable[] output = {commands_sent++};
		if (!dataProcessed(System.currentTimeMillis(), output))
			logger.warn("command could not be stored in the database");
		
		if (ret)
			return new InputInfo(getActiveAddressBean().toString(), "MIG message upload successfull", ret);
		else
			return new InputInfo(getActiveAddressBean().toString(), "MIG message upload not successfull", ret);
	}

	
    private byte[] createTOSpacket(int moteId, int amType, byte[] data) throws IOException {
		if (amType < 0) {
		    throw new IOException("unknown AM type for message");
		}
	
		// which TinyOS messages version are we generating?
		if (tinyos1x_platform != null) {
			// the following functionality has been extracted from net.tinyos1x.message.Sender
			// from the send function
			TOSMsg packet ;
		      
			// normal case, a PhoenixSource
			// hack: we don't leave any space for the crc, so
			// numElements_data() will be wrong. But we access the
			// data area via dataSet/dataGet, so we're ok.
			packet = createTOSMsg ( template.offset_data ( 0 ) + data.length ) ;

			// message header: destination, group id, and message type
			packet.set_addr ( moteId ) ;
			packet.set_group ( (short) tinyos1x_groupId ) ;
			packet.set_type ( ( short ) amType ) ;
			packet.set_length ( ( short ) data.length ) ;
		      
			packet.dataSet ( data , 0 , packet.offset_data ( 0 ) , data.length ) ;

			return packet.dataGet();
		}
		else {
			// the following functionality has been extracted from net.tinyos.message.Sender
			// from the send function
			SerialPacket packet =
			    new SerialPacket(SerialPacket.offset_data(0) + data.length);
			packet.set_header_dest(moteId);
			packet.set_header_type((short)amType);
			packet.set_header_length((short)data.length);
			packet.dataSet(data, 0, SerialPacket.offset_data(0), data.length);
		
			byte[] packetData = packet.dataGet();
			byte[] fullPacket = new byte[packetData.length + 1];
			fullPacket[0] = Serial.TOS_SERIAL_ACTIVE_MESSAGE_ID;
			System.arraycopy(packetData, 0, fullPacket, 1, packetData.length);
			return fullPacket;
		}
    }

//	
// following functions are only used for TinyOS1.x messages	
//
	
	TOSMsg instantiateTOSMsg ( Class<?> [] cArgs , Object [] args ) {
	   	try {
		   	Class<?> msgCls ;
         
		   	msgCls = Class.forName ( "net.tinyos1x.message." + tinyos1x_platform + ".TOSMsg" ) ;
         
		   	Constructor<?> c = msgCls.getConstructor ( cArgs ) ;
		   	return (TOSMsg) c.newInstance ( args ) ;
	   	}
	   	catch ( ClassNotFoundException e ) {
		   	System.err.println ( "Could not find a platform specific version of TOSMsg" ) ;
		   	System.err.println ( e ) ;
		   	e.printStackTrace () ;
	   	}
	   	catch ( NoSuchMethodException e ) {
		   	System.err.println ( "Could not locate the appropriate constructor; check the class " + "net.tinyos1x.message." + tinyos1x_platform
                              + ".TOSMsg" ) ;
		   	e.printStackTrace () ;
	   	}
	   	catch ( InstantiationException e ) {
		   	System.err.println ( "Could not instantiate the class: " + e ) ;
		   	e.printStackTrace () ;
      	}
      	catch ( IllegalAccessException e ) {
    	  	System.err.println ( "Illegal access: " + e ) ;
         	e.printStackTrace () ;
      	}
      	catch ( InvocationTargetException e ) {
      		System.err.println ( "Reflection problems: " + e ) ;
         	e.printStackTrace () ;
      	}
      	return null ;
   	}

    public TOSMsg createTOSMsg ( int data_length ) {
    	Object [] initArgs = new Object [ 1 ] ;
    	Class<?> [] cArgs = new Class [ 1 ] ;
    	cArgs [ 0 ] = Integer.TYPE ;
    	initArgs [ 0 ] = new Integer ( data_length ) ;
    	
    	return instantiateTOSMsg ( cArgs , initArgs ) ;
    }

}
