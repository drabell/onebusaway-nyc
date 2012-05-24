package org.onebusaway.nyc.webapp.actions.admin.bundles;

import java.util.List;


import org.apache.struts2.convention.annotation.Result;
import org.apache.struts2.convention.annotation.Results;
import org.onebusaway.nyc.admin.model.BundleRequest;
import org.onebusaway.nyc.admin.model.BundleResponse;
import org.onebusaway.nyc.admin.service.BundleRequestService;
import org.onebusaway.nyc.admin.service.FileService;
import org.onebusaway.nyc.webapp.actions.OneBusAwayNYCAdminActionSupport;
import org.springframework.beans.factory.annotation.Autowired;


/**
 * Base class to hold properties and methods required across all bundle building UI pages
 * @author abelsare
 *
 */
@Results({@Result(type = "redirectAction", name = "redirect", params = {
	     "actionName", "manage-bundles"})})
public class ManageBundlesAction extends OneBusAwayNYCAdminActionSupport {

	private static final long serialVersionUID = 1L;
	//To hold the final directory name 
	private String bundleDirectory;
	private String createDirectoryMessage;
	//Holds the value entered in the text box
	private String directoryName;
	private boolean directoryCreated;
	private boolean productionTarget;
	private String comments;
	private FileService fileService;
	private BundleRequestService bundleRequestService;
	private static final int MAX_RESULTS = -1;
	
	
	/**
	 * Creates directory for uploading bundles on AWS
	 */
	public void createDirectory() {
		if(fileService.bundleDirectoryExists(directoryName)) {
			createDirectoryMessage = directoryName + " already exists. Please try again!";
		} else {
			//Create the directory if it does not exist.
			directoryCreated = fileService.createBundleDirectory(directoryName);
			selectDirectory();
			if(directoryCreated) {
				createDirectoryMessage = "Successfully created new directory: " +directoryName;
			} else {
				createDirectoryMessage = "Unable to create direcory: " +directoryName;
			}
		}
	}
	
	/**
	 * Returns the existing directories in the current bucket on AWS
	 * @return list of existing directories
	 */
	public List<String[]> getExistingDirectories() {
		List<String[]> existingDirectories = fileService.listBundleDirectories(MAX_RESULTS);
		return existingDirectories;
	}
	
	
	/**
	 * Validates a bundle request and generates a response
	 * @return bundle response as validation result.
	 */
	public BundleResponse validateBundle() { 
		BundleRequest bundleRequest = new BundleRequest();
		bundleRequest.setBundleDirectory(bundleDirectory);
		return bundleRequestService.validate(bundleRequest);
	}
	
	/**
	 * Stores the newly created or selected directory name from the UI.
	 */
	public void selectDirectory() {
		bundleDirectory = directoryName;
	}
	
	/**
	 * @return the createDirectoryMessage
	 */
	public String getCreateDirectoryMessage() {
		return createDirectoryMessage;
	}
	
	/**
	 * @param createDirectoryMessage the createDirectoryMessage to set
	 */
	public void setCreateDirectoryMessage(String createDirectoryMessage) {
		this.createDirectoryMessage = createDirectoryMessage;
	}
	
	/**
	 * @return the directoryName
	 */
	public String getDirectoryName() {
		return directoryName;
	}
	
	/**
	 * @param directoryName the directoryName to set
	 */
	public void setDirectoryName(String directoryName) {
		this.directoryName = directoryName;
	}
	
	/**
	 * @return the directoryCreated
	 */
	public boolean isDirectoryCreated() {
		return directoryCreated;
	}
	
	/**
	 * @param directoryCreated the directoryCreated to set
	 */
	public void setDirectoryCreated(boolean directoryCreated) {
		this.directoryCreated = directoryCreated;
	}
	
	/**
	 * @return the productionTarget
	 */
	public boolean isProductionTarget() {
		return productionTarget;
	}
	
	/**
	 * @param productionTarget the productionTarget to set
	 */
	public void setProductionTarget(boolean productionTarget) {
		this.productionTarget = productionTarget;
	}
	
	/**
	 * @return the comments
	 */
	public String getComments() {
		return comments;
	}
	
	/**
	 * @param comments the comments to set
	 */
	public void setComments(String comments) {
		this.comments = comments;
	}
	
	/**
	 * @param fileService the fileService to set
	 */
	@Autowired
	public void setFileService(FileService fileService) {
		this.fileService = fileService;
	}
	
	/**
	 * @return the bundleDirectory
	 */
	public String getBundleDirectory() {
		return bundleDirectory;
	}
	
	/**
	 * @param bundleDirectory the bundleDirectory to set
	 */
	public void setBundleDirectory(String bundleDirectory) {
		this.bundleDirectory = bundleDirectory;
	}
	

	/**
	 * Injects {@link BundleRequestService}
	 * @param bundleRequestService the bundleRequestService to set
	 */
	@Autowired
	public void setBundleRequestService(BundleRequestService bundleRequestService) {
		this.bundleRequestService = bundleRequestService;
	}
	

}