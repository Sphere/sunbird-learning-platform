/**
 * 
 */
package org.ekstep.tools.loader.destination;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ekstep.tools.loader.service.ExecutionContext;
import org.ekstep.tools.loader.service.OrganisationServiceImpl;
import org.ekstep.tools.loader.service.ProgressCallback;
import org.ekstep.tools.loader.service.Record;
import org.ekstep.tools.loader.shell.ShellContext;
import org.ekstep.tools.loader.utils.JsonUtil;

import com.google.gson.JsonObject;
import com.typesafe.config.Config;

/**
 * @author pradyumna
 *
 */
public class OrgMemberDestination implements Destination {

	private Logger logger = LogManager.getLogger(OrgMemberDestination.class);

	private Config config = null;
	private String user = null;
	private ExecutionContext context = null;
	private ShellContext shellContext = null;
	private FileWriter outputFile;
	private File file = null;

	/**
	 * 
	 */
	public OrgMemberDestination() {
		file = new File("OrgMemberOutput.csv");
		if (!file.exists()) {
			try {
				file.createNewFile();
				outputFile = new FileWriter(file, true);
				outputFile.write("OrganisationID , UserId, Status \n");
				outputFile.close();
			} catch (IOException e) {
				logger.debug("Error while creating file");
			}

		}
	}

	/* (non-Javadoc)
	 * @see org.ekstep.tools.loader.destination.Destination#process(java.util.List, org.ekstep.tools.loader.service.ProgressCallback)
	 */
	@Override
	public void process(List<Record> data, ProgressCallback callback) {
		shellContext = ShellContext.getInstance();
		config = shellContext.getCurrentConfig().resolve();
		user = shellContext.getCurrentUser();
		context = new ExecutionContext(config, user);
		String orgID = null, userId = null;
		int rowNum = 1;
		int totalRows = data.size();

		OrganisationServiceImpl service = new OrganisationServiceImpl();

		service.init(context);
		writeOutput("\n------------------- Begin ::" + LocalDate.now() + " " + LocalTime.now() + "------------\n");
		for (Record record : data) {
			try {
				JsonObject member = record.getJsonData();
				orgID = JsonUtil.getFromObject(member, "orgId");
				userId = JsonUtil.getFromObject(member, "userId");
				String response = service.addOrgMember(member, context);
				writeOutput(orgID + "," + userId + "," + response);
			} catch (Exception e) {
				writeOutput(orgID + "," + userId + "," + e.getMessage());
			}
			callback.progress(totalRows, rowNum++);

		}
		writeOutput("\n------------------- END ::" + LocalDate.now() + " " + LocalTime.now() + "------------\n");
	}

	/**
	 * @param string
	 * @throws IOException
	 */
	private void writeOutput(String output) {
		try {
			outputFile = new FileWriter(file, true);
			outputFile.append(output + "\n");
			outputFile.close();
			logger.debug("OrgMember Output  :: " + output);
		} catch (IOException e) {
			logger.debug("error while wrting to outputfile" + e.getMessage());
		}

	}
}
