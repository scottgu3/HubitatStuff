/**
 *  Stripped Down Tuya Zigbee Garage Door Opener driver for Hubitat
 *
 *  https://community.hubitat.com/t/tuya-zigbee-garage-door-opener/95579
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 * 
 * ver. 1.0.0 2022-06-18 kkossev  - Inital test version
 * ver. 1.0.1 2022-06-19 kkossev  - fixed Contact status open/close; added doorTimeout preference, default 15s; improved debug loging; PowerSource capability'; contact open/close status determines door state!
 * ver. 1.0.2 2022-06-20 kkossev  - ignore Open command if the sensor is open; ignore Close command if the sensor is closed.
 * ver. 1.0.3 2022-06-26 kkossev  - fixed new device exceptions bug; warnings in Debug logs only; Debug logs are off by default.
 * ver. 1.0.4 2022-07-06 kkossev  - on() command opens the door if it was closed, off() command closes the door if it was open; 'contact is open/closed' info and warning logs are shown only on contact state change;
 * ver. 1.0.5 2023-10-09 kkossev  - added _TZE204_nklqjk62 fingerprint
 * ver. 1.1.0 2024-07-15 kkossev  - (dev.branch) added commands setContact() and setDoor()
 * ver. 1.1.0V 2024-11-06 scottgu3 - Stripped down variant for my particular use case
*/

def version() { "1.1.0V" }
def timeStamp() {"2024/11/07 07:19 PM"}

import hubitat.device.HubAction
import hubitat.device.Protocol
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final Boolean _DEBUG = false
@Field static final Integer PULSE_TIMER  = 1000         // milliseconds

metadata {
    definition (name: "Tuya Zigbee GDO Stripped", namespace: "scottgu3", author: "Krassimir Kossev - modified by Scott Guthrie", importUrl: "https://raw.githubusercontent.com/scottgu3/HubitatStuff/Tuya%20Zigbee%20Garage%20Door%20Opener%20Stripped/Tuya%20Zigbee%20Garage%20Door%20Opener%20Stripped.groovy", singleThreaded: true ) {
        capability "Actuator"
        capability "ContactSensor"
        capability "Configuration"
        capability "PowerSource"

        if (_DEBUG) {
            command "initialize", [[name: "Manually initialize the device after switching drivers.  \n\r     ***** Will load device default values! *****" ]]
        }
        command "setContact", [[name:"Set Contact", type: "ENUM", description: "Select Contact State", constraints: ["--- Select ---", "open", "closed" ]]]      
        fingerprint profileId:"0104", model:"TS0601", manufacturer:"_TZE200_wfxuhoea", endpointId:"01", inClusters:"0004,0005,EF00,0000", outClusters:"0019,000A", application:"42", deviceJoinName: "LoraTap Garage Door Opener"        // LoraTap GDC311ZBQ1
        fingerprint profileId:"0104", model:"TS0601", manufacturer:"_TZE200_nklqjk62", endpointId:"01", inClusters:"0004,0005,EF00,0000", outClusters:"0019,000A", application:"42", deviceJoinName: "MatSee Garage Door Opener"         // MatSee PJ-ZGD01
        fingerprint profileId:"0104", model:"TS0601", manufacturer:"_TZE204_nklqjk62", endpointId:"01", inClusters:"0004,0005,EF00,0000", outClusters:"0019,000A", application:"4A", deviceJoinName: "MatSee Garage Door Opener"         // MatSee PJ-ZGD01
        
    }

    preferences {
        input (name: "logEnable", type: "bool", title: "<b>Debug logging</b>", description: "<i>Debug information, useful for troubleshooting. Recommended value is <b>false</b></i>", defaultValue: false)
        input (name: "txtEnable", type: "bool", title: "<b>Description text logging</b>", description: "<i>Display measured values in HE log page. Recommended value is <b>true</b></i>", defaultValue: true)
    }
}


private getCLUSTER_TUYA() { 0xEF00 }

// Parse incoming device messages to generate events
def parse(String description) {
    if (logEnable == true) log.debug "${device.displayName} parse: description is $description"
    checkDriverVersion()
    setPresent()
	if (description?.startsWith('catchall:') || description?.startsWith('read attr -')) {
        def descMap = [:]
        try {
            descMap = zigbee.parseDescriptionAsMap(description)
        }
        catch (e) {
            log.warn "${device.displayName} parse: exception caught while parsing descMap:  ${descMap}"
            return null
        }
		if (descMap?.clusterInt == CLUSTER_TUYA) {
        	if (logEnable) log.debug "${device.displayName} parse Tuya Cluster: descMap = $descMap"
			if ( descMap?.command in ["00", "01", "02"] ) {
                def transid = zigbee.convertHexToInt(descMap?.data[1])
                def dp = zigbee.convertHexToInt(descMap?.data[2])
                def dp_id = zigbee.convertHexToInt(descMap?.data[3])
                def fncmd = getTuyaAttributeValue(descMap?.data)
                if (logEnable) log.trace "${device.displayName} Tuya cluster dp_id=${dp_id} dp=${dp} fncmd=${fncmd}"
                switch (dp) {
                    case 0x01 : // Relay / trigger switch
                        def value = fncmd == 1 ? "on" : "off"
                        if (logEnable) log.debug "${device.displayName} received Relay / trigger switch report dp_id=${dp_id} dp=${dp} fncmd=${fncmd}"
                        // sendSwitchEvent(value) // version 1.0.4
                        break
                    case 0x02 : // unknown, received as a confirmation of the relay on/off commands? Payload is always 0
                        if (logEnable) log.debug "${device.displayName} received confirmation report dp_id=${dp_id} dp=${dp} fncmd=${fncmd}"
                        break
                    case 0x03 : // Contact
              
			} // if command in ["00", "01", "02"]
            else if (descMap?.clusterInt==CLUSTER_TUYA && descMap?.command == "0B") {    // ZCL Command Default Response
                if (logEnable) log.debug "${device.displayName} device received Tuya cluster ZCL command 0x${descMap?.command} response: 0x${descMap?.data[1]} status: ${descMap?.data[1]=='00'?'success':'FAILURE'} data: ${descMap?.data}"
            } 
            else {
                if (logEnable) log.warn "${device.displayName} <b>NOT PROCESSED COMMAND Tuya cmd ${descMap?.command}</b> : dp=${dp} value=${fncmd} descMap.data = ${descMap?.data}" 
            }
		} // if Tuya cluster
        else {
            if (descMap?.cluster == "0000" && descMap?.attrId == "0001") {
                if (logEnable) log.debug "${device.displayName} Tuya check-in: ${descMap}"
            }
            else {
                if (logEnable) log.debug "${device.displayName} parsed non-Tuya cluster: descMap = $descMap"
            }
        }
	} // if catchall or read attr
}   

private int getTuyaAttributeValue(ArrayList _data) {
    int retValue = 0
    
    if (_data.size() >= 6) {
        int dataLength = _data[5] as Integer
        int power = 1;
        for (i in dataLength..1) {
            retValue = retValue + power * zigbee.convertHexToInt(_data[i+5])
            power = power * 256
        }
    }
    return retValue
}
def pulseOn() {
    if (logEnable) log.debug "${device.displayName} pulseOn()"
    runInMillis( PULSE_TIMER, pulseOff, [overwrite: true])
    relayOn()
}

def pulseOff() {
    if (logEnable) log.debug "${device.displayName} pulseOff()"
    relayOff()
}

def sendContactEvent(state, isDigital=false) {
    def map = [:]
    map.name = "contact"
    map.value = state    // open or closed
    map.type = isDigital == true ? "digital" : "physical"
    map.descriptionText = "${device.displayName} contact is ${map.value}"
    if (isDigital) { map.descriptionText += " [${map.type}]" }
    if (device?.currentState('contact')?.value != state) {
        if (txtEnable) {log.info "${device.displayName} ${map.descriptionText} (${map.type})"}
    }
    else {
        if (logEnable) {log.info "${device.displayName} heartbeat: contact is ${state}"}
    }
    sendEvent(map)
}

void initializeVars( boolean fullInit = true ) {
    if (logEnable==true) { log.info "${device.displayName} InitializeVars()... fullInit = ${fullInit}" }
    if (fullInit == true ) {
        state.clear()
        state.driverVersion = driverVersionAndTimeStamp()
    }
    if (fullInit == true || settings?.logEnable == null) { device.updateSetting("logEnable", false) }
    if (fullInit == true || settings?.txtEnable == null) { device.updateSetting("txtEnable", true) }
    
    if (device?.currentState('contact')?.value == null ) {
        sendEvent(name : "contact",	value : "?", isStateChange : true)
    }
}

def initialize() {
    if (txtEnable==true) log.info "${device.displayName} Initialize()..."
    unschedule()
    initializeVars()
	sendEvent(name: "contact", value: "closed")
    sendEvent(name : "powerSource",	value : "mains")
    updated()            // calls also configure()
}

void logsOff(){
    log.warn "${device.displayName} Debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def tuyaBlackMagic() {
    return zigbee.readAttribute(0x0000, [0x0004, 0x000, 0x0001, 0x0005, 0x0007, 0xfffe], [:], delay=200)
}

def configure() {
    if (txtEnable==true) log.info "${device.displayName} configure().."
    checkDriverVersion()
    List<String> cmds = []
    cmds += tuyaBlackMagic()
    sendZigbeeCommands(cmds)
}

def updated() {
    checkDriverVersion()
    log.info "${device.displayName} debug logging is: ${logEnable == true}"
    log.info "${device.displayName} description logging is: ${txtEnable == true}"
    if (txtEnable) log.info "${device.displayName} Updated..."
    if (logEnable) runIn(86400, logsOff, [overwrite: true])

}

def installed() {
    log.info "Installing..."
    log.info "Debug logging will be automatically disabled after 24 hours"
    device.updateSetting("logEnable",[type:"bool",value:"false"])
    device.updateSetting("txtEnable",[type:"bool",value:"true"])
    sendEvent(name : "powerSource",	value : "?", isStateChange : true)
    if (logEnable) runIn(86400, logsOff, [overwrite: true])
}


def driverVersionAndTimeStamp() {version()+' '+timeStamp()}

def checkDriverVersion() {
    if (state.driverVersion != null && driverVersionAndTimeStamp() == state.driverVersion) {
        //log.trace "${device.displayName} driverVersion is the same ${driverVersionAndTimeStamp()}"
    }
    else {
        if (txtEnable==true) log.info "${device.displayName} updating the settings from driver version ${state.driverVersion} to ${driverVersionAndTimeStamp()}"
        initializeVars( fullInit = false ) 
        state.driverVersion = driverVersionAndTimeStamp()
    }
}

// called when any event was received from the Zigbee device in parse() method..
def setPresent() {
    sendEvent(name : "powerSource",	value : "mains", isStateChange : false)
}

void sendZigbeeCommands(List<String> cmds) {
    if (logEnable) {log.trace "${device.displayName} sendZigbeeCommands : ${cmds}"}
	sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

def setContact( mode ) {
    if (mode in ['open', 'closed']) {
        sendContactEvent(mode, isDigital=true)
    }
    else {
        if (logEnable) log.warn "${device.displayName} please select the Contact state"
    }
    
}
    
}
