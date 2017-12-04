/**
 *  Hive Motion Sensor
 *
 *  Copyright 2017 Sachin Wadhwa
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
	definition (name: "Hive Motion Sensor", namespace: "swadhwa", author: "Sachin Wadhwa") {
		capability "Motion Sensor"
		capability "Sensor"
		capability "Battery"
		capability "Refresh"
		capability "Temperature Measurement"

		fingerprint inClusters: "0000,0001,0003,0009,0500,0020", manufacturer: "HiveHome.com", model: "MOT003"
	}

	// simulator metadata
	simulator {
		status "active": "zone report :: type: 19 value: 0031"
		status "inactive": "zone report :: type: 19 value: 0030"
	}
    
	preferences {
		section {
			input title: "Motion Timeout", description: "These devices don't report when motion stops, so it's necessary to have a timer to report that motion has stopped. You can adjust how long this is below.", displayDuringSetup: false, type: "paragraph", element: "paragraph"
			input "motionStopTime", "number", title: "Seconds", range: "*..*", displayDuringSetup: false, defaultValue: 30
		}
	}

	// UI tile definitions
	tiles(scale: 2) {
		multiAttributeTile(name:"motion", type: "generic", width: 6, height: 4){
			tileAttribute ("device.motion", key: "PRIMARY_CONTROL") {
				attributeState "active", label:'motion', icon:"st.motion.motion.active", backgroundColor:"#53a7c0"
				attributeState "inactive", label:'no motion', icon:"st.motion.motion.inactive", backgroundColor:"#ffffff"
			}
		}

  		valueTile("temperature", "device.temperature", defaultState: true, width: 2, height: 2) {
			state("temperature", label:'${currentValue}°', unit:"dC",
				backgroundColors:[
					[value: 0, color: "#153591"],
					[value: 7, color: "#1e9cbb"],
					[value: 15, color: "#90d2a7"],
					[value: 20, color: "#44b621"],
					[value: 25, color: "#f1d801"],
					[value: 29, color: "#d04e00"],
					[value: 32, color: "#bc2323"]
				], icon:"st.Weather.weather2"
			)
		}
      

		valueTile("battery", "device.battery", inactiveLabel: true, decoration: "flat", width: 2, height: 2) {
			state("battery", label: '${currentValue}', icon:"st.Appliances.appliances17")
		}
		
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
		}

		main(["motion", "temperature"])
		details(["motion", "temperature", "battery", "refresh"])	}
}

// Parse incoming device messages to generate events
def parse(String description) {
    def name = null
	def value = description
	def descriptionText = null
    log.debug "Parsing: ${description}"
    
	Map map = [:]

	List listMap = []
	List listResult = []
    
	if (zigbee.isZoneType19(description)) {
		name = "motion"
		def isActive = zigbee.translateStatusZoneType19(description)
        
		value = isActive ? "active" : "inactive"
		descriptionText = getDescriptionText(isActive)
	} else if(description?.startsWith("read attr -")) {
    	map = parseReportAttributeMessage(description)
	}
	else if (description?.startsWith('temperature: ')) {
		map = parseCustomMessage(description)
	}

	if (value == "active") {
    	def timeout = 30
        
        if (motionStopTime)
        	timeout = motionStopTime
        
        log.debug "Stopping motion in ${timeout} seconds"
    	runIn(timeout, stopMotion)
	}

	def result = createEvent(
		name: name,
		value: value,
		descriptionText: descriptionText
	)
    
    listResult << result
    
    if (listMap) {
        for (msg in listMap) {
            listResult << createEvent(msg)
        }
    }
    else if (map) {
        listResult << createEvent(map)
    }

	log.debug "Parse returned ${result?.descriptionText}"
	return listResult
}

def stopMotion() {
	def description = getDescriptionText(false)
	log.debug description
    sendEvent(name:"motion", value: "inactive", descriptionText: description)
}

private getDescriptionText(isActive) {
	return isActive ? "${device.displayName} detected motion" : "${device.displayName} motion has stopped"
}

private Map parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) {
		map, param -> def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	Map resultMap = [:]

	log.info "IN parseReportAttributeMessage()"
	log.debug "descMap ${descMap}"

	switch(descMap.cluster) {
		case "0001":
			log.debug "Battery status reported"
			if(descMap.attrId == "0020") {
				resultMap.name = 'battery'
				resultMap.value = getBatteryResult(Integer.parseInt(descMap.value, 16)).value
                log.debug "Battery Percentage convert to ${resultMap.value}%"
			}
			break
		default:
			log.info descMap.cluster
			log.info "cluster1"
			break
	}

	log.info "OUT parseReportAttributeMessage()"
	return resultMap
}

private Map parseCustomMessage(String description) {
	Map resultMap = [:]
	if (description?.startsWith('temperature: ')) {
		def value = (description - "temperature: ").trim()
        if (value.isNumber() && (value.toString() != "0.00"))  {
        	if (getTemperatureScale() == "F") {
            	value = celsiusToFahrenheit(value.toFloat()) as Float
			}
			resultMap = getTemperatureResult(value)
            return resultMap
        } else {
        	log.warn "invalid temperature: ${value}"
        }
		resultMap = getTemperatureResult(value)
	}
	return resultMap
}

def getTemperature(value) {
	def celsius = Integer.parseInt(value, 16).shortValue() / 100
	if(getTemperatureScale() == "C"){
		return celsius
	} else {
		return celsiusToFahrenheit(celsius) as Integer
	}
}

private Map getTemperatureResult(value) {
	if (tempOffset) {
		def offset = tempOffset as float
		def v = value as float
		value = v + offset
	}
    def descriptionText
    if ( temperatureScale == 'C' )
    	descriptionText = '{{ device.displayName }} was {{ value }}°C'
    else
    	descriptionText = '{{ device.displayName }} was {{ value }}°F'

	return [
		name: 'temperature',
		value: value,
		descriptionText: descriptionText,
		translatable: true,
		unit: temperatureScale
	]
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

def refresh() {
	log.debug "refresh called"

    return zigbee.writeAttribute(0x0500, 0x0010, 0xf0, swapEndianHex(device.hub.zigbeeEui)) + //IAS sset address
    	zigbee.configureReporting(0x0406, 0x0000, 0x18, 0, 600, null) + //Occupancy 8bit bitmap
        zigbee.configureReporting(0x0402, 0x0000, 0x29, 30, 600, 1) + //Temp signed 16bit int
        zigbee.configureReporting(0x0001, 0x0020, 0x20, 10, 600, 1) + //Power unsigned 8bit
       	zigbee.configureReporting(0x0500, 0x0002, 0x19, 0, 30, null) //IAS 16bit bitmap
}

private getEndpointId() {
	new BigInteger(device.endpointId, 16).toString()
}

private hex(value) {
	new BigInteger(Math.round(value).toString()).toString(16)
}

private String swapEndianHex(String hex) {
	reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
	int i = 0;
	int j = array.length - 1;
	byte tmp;
	while (j > i) {
		tmp = array[j];
		array[j] = array[i];
		array[i] = tmp;
		j--;
		i++;
	}
	return array
}

private Map getBatteryResult(rawValue) {
	log.debug "Battery rawValue = ${rawValue}"
	def linkText = getLinkText(device)

	def result = [
		name: 'battery',
		value: '--',
		translatable: true
	]

	def volts = rawValue / 10

	if (rawValue == 0 || rawValue == 255) {}
	else {
		if (volts > 3.5) {
			result.descriptionText = "{{ device.displayName }} battery has too much power: (> 3.5) volts."
		}
		else {
			if (device.getDataValue("manufacturer") == "SmartThings") {
				volts = rawValue // For the batteryMap to work the key needs to be an int
				def batteryMap = [28:100, 27:100, 26:100, 25:90, 24:90, 23:70,
								  22:70, 21:50, 20:50, 19:30, 18:30, 17:15, 16:1, 15:0]
				def minVolts = 15
				def maxVolts = 28

				if (volts < minVolts)
					volts = minVolts
				else if (volts > maxVolts)
					volts = maxVolts
				def pct = batteryMap[volts]
				if (pct != null) {
					result.value = pct
                    def value = pct
					result.descriptionText = "${value}"
				}
			}
			else {
				def minVolts = 2.1
				def maxVolts = 3.0
				def pct = (volts - minVolts) / (maxVolts - minVolts)
				def roundedPct = Math.round(pct * 100)
				if (roundedPct <= 0)
					roundedPct = 1
				result.value = Math.min(100, roundedPct)
				result.descriptionText = "${value}%"
			}
		}
	}

	return result
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {

	if (state.lastActivity < (now() - (1000 * device.currentValue("checkInterval"))) ){
		log.info "ping, alive=no, lastActivity=${state.lastActivity}"
		state.lastActivity = null
		return zigbee.readAttribute(0x001, 0x0020) // Read the Battery Level
	} else {
		log.info "ping, alive=yes, lastActivity=${state.lastActivity}"
		sendEvent(name: "deviceWatch-lastActivity", value: state.lastActivity, description: "Last Activity is on ${new Date((long)state.lastActivity)}", displayed: false, isStateChange: true)
	}
}
