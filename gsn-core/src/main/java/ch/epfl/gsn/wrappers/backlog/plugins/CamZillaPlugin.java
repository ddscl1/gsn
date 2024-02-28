package ch.epfl.gsn.wrappers.backlog.plugins;

import ch.epfl.gsn.beans.DataField;
import ch.epfl.gsn.beans.InputInfo;

import java.io.IOException;
import java.io.Serializable;
import java.text.DecimalFormat;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


/**
 * This plugin listens for incoming CamZilla messages and offers the functionality to
 * upload CamZilla tasks.
 * 
 * @author Tonio Gsell
 */
public class CamZillaPlugin extends AbstractPlugin {
	
	private static final short TASK_MESSAGE = 0;
	private static final short POWER_MESSAGE = 1;

	private static final short PANORAMA_TASK = 0;
	private static final short PICTURE_TASK = 1;
	private static final short POSITIONING_TASK = 2;
	private static final short MODE_TASK = 3;
	private static final short CALIBRATION_TASK = 4;
	
	private static DataField[] dataField = {
			new DataField("TIMESTAMP", "BIGINT"),
			new DataField("GENERATION_TIME", "BIGINT"),
			new DataField("DEVICE_ID", "INTEGER"),

			new DataField("COMMAND", "VARCHAR(127)"),
			new DataField("INFO", "VARCHAR(255)"),
			new DataField("X", "VARCHAR(8)"),
			new DataField("Y", "VARCHAR(8)"),
			new DataField("START_X", "VARCHAR(8)"),
			new DataField("START_Y", "VARCHAR(8)"),
			new DataField("PICTURES_X", "SMALLINT"),
			new DataField("PICTURES_Y", "SMALLINT"),
			new DataField("ROTATION_X", "VARCHAR(8)"),
			new DataField("ROTATION_Y", "VARCHAR(8)"),
			new DataField("DELAY", "SMALLINT"),
			new DataField("BATCH_DOWNLOAD", "SMALLINT"),
			new DataField("GPHOTO2_CONFIG", "BINARY")};

	private final transient Logger logger = LoggerFactory.getLogger( CamZillaPlugin.class );

	@Override
	public short getMessageType() {
		return ch.epfl.gsn.wrappers.backlog.BackLogMessage.CAMZILLA_MESSAGE_TYPE;
	}

	@Override
	public DataField[] getOutputFormat() {
		return dataField;
	}

	@Override
	public String getPluginName() {
		return "CamZillaPlugin";
	}

	@Override
	public boolean messageReceived(int deviceId, long timestamp, Serializable[] data) {
		try {
			DecimalFormat df = new DecimalFormat( "0.0" );
			String x = null, y = null, start_x = null, start_y = null, rotation_x = null, rotation_y = null;
			byte[] gphoto2conf = null;
			if (data[3] != null)
				x = df.format((Double)data[3]);
			if (data[4] != null)
				y = df.format((Double)data[4]);
			if (data[5] != null)
				start_x = df.format((Double)data[5]);
			if (data[6] != null)
				start_y = df.format((Double)data[6]);
			if (data[9] != null)
				rotation_x = df.format((Double)data[9]);
			if (data[10] != null)
				rotation_y = df.format((Double)data[10]);
			if (data[13] != null)
				gphoto2conf = ((String)data[13]).getBytes("UTF-8");
			
			if( dataProcessed(System.currentTimeMillis(), new Serializable[]{timestamp, toLong(data[0]), deviceId, (String)data[1], (String)data[2], x, y, start_x, start_y, toShort(data[7]), toShort(data[8]), rotation_x, rotation_y, toShort(data[11]), toShort(data[12]), gphoto2conf}) ) {
				ackMessage(timestamp, super.priority);
				return true;
			} else {
				logger.warn("The message with timestamp >" + timestamp + "< could not be stored in the database.");
			}
		} catch (Exception e) {
			logger.error(e.getMessage());
		}
		return false;
	}
	
	
	@Override
	public InputInfo sendToPlugin(String action, String[] paramNames, Object[] paramValues) {
		Serializable[] command = null;
		if( action.compareToIgnoreCase("panorama_picture") == 0 ) {
			String camera = "", sx = "", sy = "", px = "", py = "", rx = "", ry = "", d = "", b = "0", imgquality = "", imgsize = "", aperture = "", shutter = "", iso = "", whitebalance = "", compensation = "", bracketing = "", autofocus = "", focus = "", opt = "";
			for (int i = 0 ; i < paramNames.length ; i++) {
				if( paramNames[i].compareToIgnoreCase("camera") == 0 )
					camera = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("start_x") == 0 )
					sx = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("start_y") == 0 )
					sy = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("pictures_x") == 0 )
					px = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("pictures_y") == 0 )
					py = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("rotation_x") == 0 )
					rx = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("rotation_y") == 0 )
					ry = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("delay") == 0 )
					d = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("batch_download") == 0 )
					b = "1";
				else if( paramNames[i].compareToIgnoreCase("image_quality") == 0 )
					imgquality = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("image_size") == 0 )
					imgsize = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("aperture") == 0 )
					aperture = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("shutter_speed") == 0 )
					shutter = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("iso") == 0 )
					iso = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("white_balance") == 0 )
					whitebalance = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("exposure_compensation") == 0 )
					compensation = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("bracketing") == 0 )
					bracketing = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("auto_focus") == 0 )
					autofocus = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("focus") == 0 )
					focus = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("gphoto2_config") == 0 )
					opt = (String) paramValues[i];
			}

			String str = "";
			if (!camera.trim().isEmpty())
				str = "camera("+camera+") ";
			if (!sx.trim().isEmpty() && !sy.trim().isEmpty())
				str += "start("+sx+","+sy+") ";
			if (!px.trim().isEmpty() && !py.trim().isEmpty())
				str += "pictures("+px+","+py+") ";
			if (!rx.trim().isEmpty() && !ry.trim().isEmpty())
				str += "rotation("+rx+","+ry+") ";
			if (!d.trim().isEmpty())
				str += "delay("+d+") ";
			str += "batch("+b+") ";

			str += "gphoto2("+getD300sConfig(imgquality, imgsize, aperture, shutter, iso, whitebalance, compensation, bracketing, autofocus, focus, opt)+")";
			
			logger.info("uploading panorama picture task >" + str + "<");
			command = new Serializable[] {TASK_MESSAGE, PANORAMA_TASK , str};
		}
		else if ( action.compareToIgnoreCase("picture_now") == 0 ) {
			String camera = "", imgquality = "", imgsize = "", aperture = "", shutter = "", iso = "", whitebalance = "", compensation = "", bracketing = "", autofocus = "", focus = "", opt = "";
			for (int i = 0 ; i < paramNames.length ; i++) {
				if( paramNames[i].compareToIgnoreCase("camera") == 0 )
					camera = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("image_quality") == 0 )
					imgquality = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("image_size") == 0 )
					imgsize = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("aperture") == 0 )
					aperture = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("shutter_speed") == 0 )
					shutter = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("iso") == 0 )
					iso = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("white_balance") == 0 )
					whitebalance = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("exposure_compensation") == 0 )
					compensation = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("bracketing") == 0 )
					bracketing = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("auto_focus") == 0 )
					autofocus = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("focus") == 0 )
					focus = (String) paramValues[i];
				else if( paramNames[i].compareToIgnoreCase("gphoto2_config") == 0 )
					opt = (String) paramValues[i];
			}
			
			String str = "";
			if (!camera.trim().isEmpty())
				str = "camera("+camera+") ";

			str += "gphoto2("+getD300sConfig(imgquality, imgsize, aperture, shutter, iso, whitebalance, compensation, bracketing, autofocus, focus, opt)+")";
			
			logger.info("uploading picture now task >" + str + "<");
			command = new Serializable[] {TASK_MESSAGE, PICTURE_TASK, str};
		}
		else if ( action.compareToIgnoreCase("positioning") == 0 ) {
			double x = 0, y = 0;
			for (int i = 0 ; i < paramNames.length ; i++) {
				if( paramNames[i].compareToIgnoreCase("x") == 0 )
					x = new Double((String)paramValues[i]);
				else if( paramNames[i].compareToIgnoreCase("y") == 0 )
					y = new Double((String)paramValues[i]);
			}
			
			logger.info("uploading positioning task (x=" + x + ",y=" + y + ")");
			command = new Serializable[] {TASK_MESSAGE, POSITIONING_TASK, x, y};
		}
		else if ( action.compareToIgnoreCase("operating_mode") == 0 ) {
			short mode = 0;
			for (int i = 0 ; i < paramNames.length ; i++) {
				if( paramNames[i].compareToIgnoreCase("mode") == 0 )
					mode = 1;
			}
			if (mode == 0)
				logger.info("uploading automatic mode command");
			else
				logger.info("uploading manual mode command");
			command = new Serializable[] {TASK_MESSAGE, MODE_TASK, mode};
		}
		else if ( action.compareToIgnoreCase("calibration") == 0 ) {
			logger.info("uploading calibration command");
			command = new Serializable[] {TASK_MESSAGE, CALIBRATION_TASK};
		}
		else if ( action.compareToIgnoreCase("power_settings") == 0 ) {
			short camRobot = 0;
			short heater = 0;
			for (int i = 0 ; i < paramNames.length ; i++) {
				if( paramNames[i].compareToIgnoreCase("robot_and_camera") == 0 )
					camRobot = 1;
				if( paramNames[i].compareToIgnoreCase("heater") == 0 )
					heater = 1;
			}
			if (heater == 0) {
				if (camRobot == 0)
					logger.info("uploading: robot and camera power off / heater off");
				else
					logger.info("uploading robot and camera power on / heater off");
			}
			else {
				if (camRobot == 0)
					logger.info("uploading robot and camera power off / heater on");
				else
					logger.info("uploading robot and camera power on / heater on");
			}
			command = new Serializable[] {POWER_MESSAGE, camRobot, heater};
		}
		else {
			logger.warn("action >" + action + "< not supported");
			return new InputInfo(getActiveAddressBean().toString(), "action >" + action + "< not supported", false);
		}
		
		try {
			if( sendRemote(System.currentTimeMillis(), command, super.priority) ) {
				if (logger.isDebugEnabled())
					logger.debug(action + " task sent to CoreStation");
				return new InputInfo(getActiveAddressBean().toString(), action + " task sent to CoreStation", true);
			}
			else {
				logger.warn(action + " task could not be sent to CoreStation");
				return new InputInfo(getActiveAddressBean().toString(), action + " task could not be sent to CoreStation", false);
			}
		} catch (IOException e) {
			logger.warn(e.getMessage());
			return new InputInfo(getActiveAddressBean().toString(), e.getMessage(), false);
		}
	}
	
	
	private String getD300sConfig(String imagequality, String imagesize, String aperture, String shutter, String iso, String whitebalance, String compensation, String bracketing, String autofocus, String focus, String optional) {
		String ret = "/main/imgsettings/imagequality=" + imagequality + ",/main/imgsettings/imagesize=" + imagesize + ",/main/imgsettings/whitebalance=" + whitebalance + ",/main/capturesettings/exposurecompensation=" + compensation;
		
		if (aperture.equalsIgnoreCase("auto")) {
			if (shutter.equalsIgnoreCase("auto"))
				ret += ",/main/capturesettings/expprogram=1";
			else
				ret += ",/main/capturesettings/expprogram=3,/main/capturesettings/shutterspeed=" + shutter;
		}
		else {
			if (shutter.equalsIgnoreCase("auto"))
				ret += ",/main/capturesettings/expprogram=2,/main/capturesettings/f-number=" + aperture;
			else
				ret += ",/main/capturesettings/expprogram=0,/main/capturesettings/shutterspeed=" + shutter + ",/main/capturesettings/f-number=" + aperture;
		}
		
		if (iso.equalsIgnoreCase("auto"))
			ret += ",/main/imgsettings/autoiso=0";
		else
			ret += ",/main/imgsettings/autoiso=1,/main/imgsettings/iso=" + iso;
		
		if (bracketing.equalsIgnoreCase("none"))
			ret += ",/main/capturesettings/bracketing=1,/main/capturesettings/burstinterval=0";
		else
			ret += ",/main/capturesettings/bracketing=0,/main/capturesettings/bracketset=" + bracketing + ",/main/capturesettings/burstinterval=2";
		
		if (autofocus.equalsIgnoreCase("on"))
			ret += ",/main/actions/autofocusdrive=1";
		else if (!focus.trim().isEmpty())
			ret += ",/main/actions/manualfocusdrive=-" + Integer.parseInt(focus);
		
		if (!optional.isEmpty())
			ret += "," + optional;
		
		return ret;
	}
}
