/**
 *	MyQ Service Manager SmartApp
 * 
 *  Author: Jason Mok
 *  Date: 2014-12-26
 *
 ***************************
 *
 *  Copyright 2014 Jason Mok
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 **************************
 */
definition(
	name: "MyQ",
	namespace: "copy-ninja",
	author: "Jason Mok",
	description: "Connect MyQ to control your devices",
	category: "SmartThings Labs",
	iconUrl:   "http://smartthings.copyninja.net/icons/MyQ@1x.png",
	iconX2Url: "http://smartthings.copyninja.net/icons/MyQ@2x.png",
	iconX3Url: "http://smartthings.copyninja.net/icons/MyQ@3x.png"
)

preferences {
	page(name: "prefLogIn", title: "MyQ")    
	page(name: "prefListDevices", title: "MyQ")
}

/* Preferences */
def prefLogIn() {
	def showUninstall = username != null && password != null 
	return dynamicPage(name: "prefLogIn", title: "Connect to MyQ", nextPage:"prefListDevices", uninstall:showUninstall, install: false) {
		section("Login Credentials"){
			input("username", "email", title: "Username", description: "MyQ Username (email address)")
			input("password", "password", title: "Password", description: "MyQ password")
		}
		section("Gateway Brand"){
			input(name: "brand", title: "Gateway Brand", type: "enum",  metadata:[values:["Liftmaster","Chamberlain","Craftsman"]] )
		}
//		section("Advanced Options"){
//			input(name: "polling", title: "Server Polling (in Minutes)", type: "int", description: "in minutes", defaultValue: "5" )
//			paragraph "This option enables author to troubleshoot if you have problem adding devices. It allows the app to send information exchanged with MyQ server to the author. DO NOT ENABLE unless you have contacted author at jason@copyninja.net"
//			input(name:"troubleshoot", title: "Troubleshoot", type: "boolean")
//		}
	}
}

def prefListDevices() {
	if (forceLogin()) {
		def doorList = getDoorList()
		def lightList = getLightList()
		if ((doorList) || (lightList)) {
			return dynamicPage(name: "prefListDevices",  title: "Devices", install:true, uninstall:true) {
				if (doorList) {
					section("Select which garage door/gate to use"){
						input(name: "doors", type: "enum", required:false, multiple:true, metadata:[values:doorList])
					}
				} 
				if (lightList) {
					section("Select which light controller to use"){
						input(name: "lights", type: "enum", required:false, multiple:true, metadata:[values:lightList])
					}
				} 
			}
		} else {
			def devList = getDeviceList()
			return dynamicPage(name: "prefListDevices",  title: "Error!", install:true, uninstall:true) {
				section(""){
					paragraph "Could not find any supported device(s). Please report to author about these devices: " +  devList
				}
			}
		}  
	} else {
		return dynamicPage(name: "prefListDevices",  title: "Error!", install:false, uninstall:true) {
			section(""){
				paragraph "The username or password you entered is incorrect. Try again. " 
			}
		}  
	}
}

/* Initialization */
def installed() { initialize() }
def updated() { initialize() }

def uninstalled() {
	unschedule()
	def deleteDevices = getAllChildDevices()
	deleteDevices.each { deleteChildDevice(it.deviceNetworkId) }
}	

def initialize() {    
	unsubscribe()
	login()
    
	// Get initial device status in state.data
	state.polling = [  last: 0,  runNow: true ]
	state.data = [:]
    
	// Create new devices for each selected doors
	def selectedDevices = []
	def doorsList = getDoorList()
	def lightsList = getLightList()
	def deleteDevices 
   	 
	if (settings.doors) {
		if (settings.doors[0].size() > 1) {
			selectedDevices = settings.doors
		} else {
			selectedDevices.add(settings.doors)
		}
	}
    
	if (settings.lights) {
		if (settings.lights[0].size() > 1) {
			settings.lights.each { selectedDevices.add(it) }
		} else {
			selectedDevices.add(settings.lights)
		}
	}
     
	selectedDevices.each { dni ->    	
		def childDevice = getChildDevice(dni)
		if (!childDevice) {
			if (dni.contains("GarageDoorOpener")) {
				addChildDevice("copy-ninja", "MyQ Garage Door Opener", dni, null, ["name": "MyQ: " + doorsList[dni]])
			}
			if (dni.contains("LightController")) {
				addChildDevice("copy-ninja", "MyQ Light Controller", dni, null, ["name": "MyQ: " + lightsList[dni]])
			}
		} 
	}
    
	//Remove devices that are not selected in the settings
	if (!selectedDevices) {
		deleteDevices = getAllChildDevices()
	} else {
		deleteDevices = getChildDevices().findAll { !selectedDevices.contains(it.deviceNetworkId) }
	}
	deleteDevices.each { deleteChildDevice(it.deviceNetworkId) } 
	
	// Schedule refreshes
	runEvery30Minutes(scheduleRefresh)
	runEvery5Minutes(refresh)
    
	//refresh after installation
	refresh()
}

/* Access Management */
private forceLogin() {
	//Reset token and expiry
	state.session = [ 
		brandID: 0,
		brandName: settings.brand,
		securityToken: null,
		expiration: 0
	]
	state.polling = [ 
		last: now(),
		runNow: true
	]  
	state.data = [:]
    
	return doLogin()
}

private login() {
	if (!(state.session.expiration > now())) {
		return doLogin()
	} else {
		return true
	}
}

private doLogin() { 
	apiGet("/api/user/validate", [username: settings.username, password: settings.password] ) { response ->
		if (response.status == 200) {
			if (response.data.SecurityToken != null) {
				state.session.brandID = response.data.BrandId
				state.session.brandName = response.data.BrandName
				state.session.securityToken = response.data.SecurityToken
				state.session.expiration = now() + 150000
				return true
			} else {
				return false
			}
		} else {
			return false
		}
	} 	
}

// Listing all the garage doors you have in MyQ
private getDoorList() { 	    
	def deviceList = [:]
	apiGet("/api/v4/userdevicedetails/get", []) { response ->
		if (response.status == 200) {
			response.data.Devices.each { device ->
				// 2 = garage door, 5 = gate, 7 = MyQGarage(no gateway)
				if (device.MyQDeviceTypeId == 2||device.MyQDeviceTypeId == 5||device.MyQDeviceTypeId == 7) {
					def dni = [ app.id, "GarageDoorOpener", device.MyQDeviceId ].join('|')
					//log.debug "Door DNI : " +  dni
					device.Attributes.each { 
						if (it.AttributeDisplayName=="desc")	deviceList[dni] = it.Value
						if (it.AttributeDisplayName=="doorstate") { 
							state.data[dni] = [ status: it.Value, lastAction: it.UpdatedTime ]
						}
					}                    
				}
			}
		}
	}    
	return deviceList
}

// Listing all the light controller you have in MyQ
private getLightList() { 	    
	def deviceList = [:]
	apiGet("/api/v4/userdevicedetails/get", []) { response ->
		if (response.status == 200) {
			response.data.Devices.each { device ->
				if (device.MyQDeviceTypeId == 3) {
					def dni = [ app.id, "LightController", device.MyQDeviceId ].join('|')
					//log.debug "Light DNI: " + dni
					device.Attributes.each { 
						if (it.AttributeDisplayName=="desc") { deviceList[dni] = it.Value }
						if (it.AttributeDisplayName=="lightstate") {  state.data[dni] = [ status: it.Value ] }
					}                    
				}
			}
		}
	}    
	return deviceList
}

private getDeviceList() { 	    
	def deviceList = []
	apiGet("/api/v4/userdevicedetails/get", []) { response ->
		if (response.status == 200) {
			response.data.Devices.each { device ->
				log.debug "MyQDeviceTypeId : " + device.MyQDeviceTypeId.toString()
				if (!(device.MyQDeviceTypeId == 1||device.MyQDeviceTypeId == 2||device.MyQDeviceTypeId == 3||device.MyQDeviceTypeId == 5||device.MyQDeviceTypeId == 7)) {
					deviceList.add( device.MyQDeviceTypeId.toString() + "|" + device.TypeID )
				}
			}
		}
	}    
	return deviceList
}

/* api connection */
// get URL 
private getApiURL() {
	if (settings.brand == "Craftsman") {
		if (settings.troubleshoot == true) {
			return "https://craftexternal-myqdevice-com-a488dujmhryx.runscope.net"
		} else {
			return "https://craftexternal.myqdevice.com"
		}
	} else {
		if (settings.troubleshoot == true) {
			return "https://myqexternal-myqdevice-com-a488dujmhryx.runscope.net"
		} else {
		return "https://myqexternal.myqdevice.com"
		}
	}
}

private getApiAppID() {
	if (settings.brand == "Craftsman") {
		return "QH5AzY8MurrilYsbcG1f6eMTffMCm3cIEyZaSdK/TD/8SvlKAWUAmodIqa5VqVAs"
	} else {
		return "JVM/G9Nwih5BwKgNCjLxiFUQxQijAebyyg8QUHr7JOrP+tuPb8iHfRHKwTmDzHOu"
	}
}
	
// HTTP GET call
private apiGet(apiPath, apiQuery = [], callback = {}) {	
	// set up query
	apiQuery = [ appId: getApiAppID() ] + apiQuery
	if (state.session.securityToken) { apiQuery = apiQuery + [SecurityToken: state.session.securityToken ] }
    
	// set up parameters
	def apiParams = [ 
		uri: getApiURL(),
		path: apiPath,
		query: apiQuery
	]
	//log.debug "HTTP GET request: " + apiParams  
	try {
		httpGet(apiParams) { response ->
			//log.debug "HTTP GET response: " + response.data          
			callback(response)
		}
	}	catch (Error e)	{
		log.debug "API Error: $e"
	}
}

// HTTP PUT call
private apiPut(apiPath, apiBody = [], callback = {}) {    
	def encodedAppID = URLEncoder.encode(getApiAppID(), "UTF-8")
	// set up body
	apiBody = [ ApplicationId: getApiAppID() ] + apiBody
	if (state.session.securityToken) { apiBody = apiBody + [SecurityToken: state.session.securityToken ] }
    
	// set up query
	def apiQuery = [ appId: getApiAppID() ]
	if (state.session.securityToken) { apiQuery = apiQuery + [SecurityToken: state.session.securityToken ] }
    
	// set up final parameters
	def apiParams = [ 
		uri: getApiURL(),
		path: apiPath,
		contentType: "application/json; charset=utf-8",
		body: apiBody,
        query: apiQuery
	]
    
    //log.debug "HTTP PUT request: " + apiParams         
	try {
		httpPut(apiParams) { response ->
			//log.debug "HTTP PUT response: " + response.data            
			callback(response)
		}
	} catch (Error e)	{
		log.debug "API Error: $e"
	}
}

// Updates data for devices
private updateDeviceData() {    
	// automatically checks if the token has expired, if so login again
	if (login()) {        
		// Next polling time, defined in settings
		def next = (state.polling.last?:0) +  275000 //((settings.polling.toInteger() > 0 ? settings.polling.toInteger() : 1) * 60 * 1000)
		if ((now() > next) || (state.polling.runNow)) {
			// set polling states
			state.polling.last = now()
			state.polling.runNow = false
			
			// Get all the door information, updated to state.data
			def doorList = getDoorList()
			def lightList = getLightList()
			if (doorList||lightList) { 
				return true 
			} else {
				return false
			}
		} 
		return true
	} else {
		return false
	}
}

/* for SmartDevice to call */
// Refresh data
def refresh() {   
	//Update last run
	state.polling = [ 
		last: now(),
		runNow: true
	]
    
	//update device to state data
	def updated = updateDeviceData()
	//force devices to poll to get the latest status
	if (updated) { 
		// get all the children and send updates
		def childDevice = getAllChildDevices()
		childDevice.each { 
			log.debug "Polling " + it.deviceNetworkId
			it.updateDeviceStatus(state.data[it.deviceNetworkId].status)
			if (it.deviceNetworkId.contains("GarageDoorOpener")) {
				it.updateDeviceLastActivity(state.data[it.deviceNetworkId].lastAction.toLong())
			}
		}
	}    
}

// Get Device ID
def getChildDeviceID(child) {
	return child.device.deviceNetworkId.split("\\|")[2]
}


// Get single device status
def getDeviceStatus(child) {
	return state.data[child.device.deviceNetworkId].status
}


// Get single device last activity
def getDeviceLastActivity(child) {
	return state.data[child.device.deviceNetworkId].lastAction.toLong()
}

// Send command to start or stop
def sendCommand(child, attributeName, attributeValue) {
	if (login()) {	    	
		//Send command
		apiPut("/api/v4/deviceattribute/putdeviceattribute", [ MyQDeviceId: getChildDeviceID(child), AttributeName: attributeName, AttributeValue: attributeValue ]) 	

		// Schedule a refresh to verify it has been completed
		runIn(90, refresh, [overwrite: false])
		
		return true
	} 
}

// Reschedule if original schedule died off 25 minutes later
def scheduleRefresh() {
	log.debug "Last poll was "  + ((now() - state.polling.last)/60000) + " minutes ago"
	if (((state.polling.last?:0) + 1500000) < now()) {
    	if (!canSchedule()) unschedule("refresh")
		if (canSchedule()) {
			runEvery5Minutes(refresh)
			log.debug "Auto Refresh Scheduled" 
		}
	}
}
