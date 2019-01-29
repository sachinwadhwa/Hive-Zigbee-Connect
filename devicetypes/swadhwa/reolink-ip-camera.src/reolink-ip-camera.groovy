/** 
 *  
 *  Reolink IP Camera Device v1.0
 *
 *  Copyright 2018 Sachin Wadhwa
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
*/

metadata {
	definition (name: "Reolink IP Camera", namespace: "swadhwa", author: "Sachin Wadhwa") {
		capability "Image Capture"
        capability "Polling"
		capability "Sensor"
		capability "Actuator"
        
		attribute "hubactionMode", "string"
        attribute "ttl", "string"
        attribute "last_request", "number"
        attribute "last_live", "number"
	}

    preferences {
		input("CameraIP", "string", title:"Camera IP Address", description: "Please enter your camera's IP Address", required: true, displayDuringSetup: true)
		input("CameraPort", "string", title:"Camera Port", description: "Please enter your camera's Port", defaultValue: 80 , required: true, displayDuringSetup: true)
		input("CameraUser", "string", title:"Camera User", description: "Please enter your camera's username", required: false, displayDuringSetup: true)
		input("CameraPassword", "password", title:"Camera Password", description: "Please enter your camera's password", required: false, displayDuringSetup: true)
	}
    
	simulator {
    
	}
    tiles {
        standardTile("take", "device.image", width: 1, height: 1, canChangeIcon: false, inactiveLabel: true, canChangeBackground: false) {
            state "take", label: "Take", action: "Image Capture.take", icon: "st.camera.dropcam", backgroundColor: "#FFFFFF", nextState:"taking"
            state "taking", label:'Taking', action: "", icon: "st.camera.take-photo", backgroundColor: "#53a7c0"
            state "image", label: "Take", action: "Image Capture.take", icon: "st.camera.dropcam", backgroundColor: "#FFFFFF", nextState:"taking"
        }
		standardTile("refresh", "device.ttl", inactiveLabel: false, decoration: "flat") {
            state "default", action:"polling.poll", icon:"st.secondary.refresh"
        }
        standardTile("ttl", "device.ttl", inactiveLabel: false, decoration: "flat") {
            state "ttl", label:'${currentValue}'
        }
        carouselTile("cameraDetails", "device.image", width: 3, height: 2) { }
        main "take"
         details([ "take", "refresh", "ttl","cameraDetails"])
    }
}

def parse(String description) {
    log.debug "Parsing '${description}'"
    def map = [:]
	def retResult = []
	def descMap = parseDescriptionAsMap(description)
	//Image
	def imageKey = descMap["tempImageKey"] ? descMap["tempImageKey"] : descMap["key"]
	if (imageKey) {
		storeTemporaryImage(imageKey, getPictureName())
        log.debug "Image stored"
	}
    
    def c = new GregorianCalendar()
    sendEvent(name: 'last_live', value: c.time.time)
    def ping = ttl()
    sendEvent(name: 'ttl', value: ping)
    log.debug "Pinging ${device.deviceNetworkId}: ${ping}"
}

def take() {
    def hosthex = convertIPtoHex(CameraIP).toUpperCase()
    def porthex = convertPortToHex(CameraPort).toUpperCase()
    device.deviceNetworkId = "$hosthex:$porthex" 
    
    log.debug "The device id configured is: $device.deviceNetworkId"
    
    def path = "/cgi-bin/api.cgi?cmd=Snap&channel=0&username=${CameraUser}&password=${CameraPassword}&rs=" + Math.abs(new Random().nextInt()%9999999999).toString()
    //log.debug "path is: $path"
  
    def headers = [:] 
    headers.put("HOST", "$CameraIP:$CameraPort")
    try {
    def hubAction = new physicalgraph.device.HubAction(
    	method: "GET",
    	path: path,
  		headers: headers
        )	
    hubAction.options = [outputMsgToS3:true]
    log.debug "Say Cheese.."
    sendHubCommand(hubAction)
    }
    catch (Exception e) {
    	log.debug "Hit Exception $e on $hubAction"
    }
    
}

private ttl() { 
    def last_request = device.latestValue("last_request")
    if(!last_request) {
    	last_request = 0
    }
    def last_alive = device.latestValue("last_live")
    if(!last_alive) { 
    	last_alive = 0
    }
    def last_status = device.latestValue("status")
    
    def c = new GregorianCalendar()
    def ttl = c.time.time - last_request
    if(ttl > 10000 || last_status == "Down") { 
    	ttl = c.time.time - last_alive
    }
    
    def units = "ms"
    if(ttl > 10*52*7*24*60*60*1000) { 
    	return "Never"
    }
    else if(ttl > 52*7*24*60*60*1000) { 
        ttl = ttl / (52*7*24*60*60*1000)
        units = "y"
    }
    else if(ttl > 7*24*60*60*1000) { 
        ttl = ttl / (7*24*60*60*1000)
        units = "w"
    }
    else if(ttl > 24*60*60*1000) { 
        ttl = ttl / (24*60*60*1000)
        units = "d"
    }
    else if(ttl > 60*60*1000) { 
        ttl = ttl / (60*60*1000)
        units = "h"
    }
    else if(ttl > 60*1000) { 
        ttl = ttl / (60*1000)
        units = "m"
    }
    else if(ttl > 1000) { 
        ttl = ttl / 1000
        units = "s"
    }  
	def ttl_int = ttl.intValue()
    
	"${ttl_int} ${units}"
}


// handle commands
def poll() {

    def hosthex = convertIPtoHex(CameraIP).toUpperCase()
    def porthex = convertPortToHex(CameraPort).toUpperCase()
    device.deviceNetworkId = "$hosthex:$porthex" 
    
    def hubAction = new physicalgraph.device.HubAction(
    	method: "GET",
    	path: "/"
    )        
    
	def last_request = device.latestValue("last_request")
    def last_live = device.latestValue("last_live")
    if(!last_request) {
    	last_request = 0
    }
    if(!last_live) {
    	last_live = 0
    }

	def c = new GregorianCalendar()
    
    if(last_live < last_request) { 
    	//sendEvent(name: 'contact', value: "closed")   
       sendEvent(name: 'ttl', value: ttl())
    }
    sendEvent(name: 'last_request', value: c.time.time)
	sendHubCommand(hubAction)
}

def parseDescriptionAsMap(description) {
    description.split(",").inject([:]) { map, param ->
    def nameAndValue = param.split(":")
    map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
    }
}

private getPictureName() {
	def pictureUuid = java.util.UUID.randomUUID().toString().replaceAll('-', '')
    log.debug pictureUuid
    def picName = device.deviceNetworkId.replaceAll(':', '') + "_$pictureUuid" + ".jpg"
	return picName
}

private String convertIPtoHex(ipAddress) { 
    String hex = ipAddress.tokenize( '.' ).collect {  String.format( '%02x', it.toInteger() ) }.join()
    log.debug "IP address entered is $ipAddress and the converted hex code is $hex"
    return hex

}

private String convertPortToHex(port) {
	String hexport = port.toString().format( '%04x', port.toInteger() )
    log.debug hexport
    return hexport
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}


private String convertHexToIP(hex) {
	log.debug("Convert hex to ip: $hex") 
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private getHostAddress() {
	def parts = device.deviceNetworkId.split(":")
    log.debug device.deviceNetworkId
	def ip = convertHexToIP(parts[0])
	def port = convertHexToInt(parts[1])
	return ip + ":" + port
}

def refresh() {
	log.debug "Refresh called"
}
