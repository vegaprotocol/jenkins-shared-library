import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder

String disableAlerts(Map args=[:]) {
    String matcherName = ""
    String matcherValue = ""

    if (args?.node) {
        matcherName = "instance"
        matcherValue = args.node
    } else if (args?.environment) {
        matcherName = "environment"
        matcherValue = args.environment
    } else {
        throw "disableAlerts error: need to provide 'node' or 'environment' argument. Provided: ${args}"
    }

    int duration = (args?.duration ?: "20") as int // in minutes
    def start = new Date()
    def end = new Date(start.getTime() + (duration * 60 * 1000))

    String strStart = start.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
    String strEnd = end.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))

    String strResponse = sh(label: "HTTP Prometheus API: create silence",
        returnStdout: true,
        script: """#!/bin/bash -e
            curl -X POST \
                https://prom.ops.vega.xyz/alertmanager/api/v2/silences \
                -H 'Content-Type: application/json' \
                -d '{
                    "matchers": [
                    {
                        "name": "${matcherName}",
                        "value": "${matcherValue}",
                        "isRegex": false,
                        "isEqual": true
                    }
                    ],
                    "startsAt": "${strStart}",
                    "endsAt": "${strEnd}",
                    "createdBy": "Jenkins",
                    "comment": "Created by Jenkins pipeline: ${env.RUN_DISPLAY_URL}",
                    "id": null
                }'
        """
    ).trim()

    print("disableAlerts response: ${strResponse}")

    def response = new groovy.json.JsonSlurperClassic().parseText(strResponse)
    def silenceID = response["silenceID"]

    print("Disabled alerts for ${matcherName}=${matcherValue} for ${duration} minutes, until: ${strEnd} UTC. Prometheus Silence ID: ${silenceID}")

    return silenceID
}

void enableAlerts(Map args=[:]) {
    assert args?.silenceID : "enableAlerts error: missing silenceID argument. Arguments: ${args}"
    int delay = (args?.delay ?: "5") as int // in minutes

    def now = new Date()
    def end = new Date(now.getTime() + (args.delay * 60 * 1000))
    String strEnd = end.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))

    silenceConfig = getDisabledAlerts(silenceID: args.silenceID)
    silenceConfig["endsAt"] = strEnd
    String matcherName = silenceConfig["matchers"][0]["name"]
    String matcherValue = silenceConfig["matchers"][0]["value"]

    String postData = new JsonBuilder(silenceConfig).toPrettyString()

    sh label: 'HTTP Prometheus API: delete silence', script: """#!/bin/bash -e
        curl -X POST \
            https://prom.ops.vega.xyz/alertmanager/api/v2/silences \
            -H 'Content-Type: application/json' \
            -d '${postData}'
    """

    print("Alerts ${matcherName}=${matcherValue} will be enabled in ${args.delay} minutes, at ${strEnd} UTC. Prometheus Silence ID: ${args.silenceID}")
}

Object getDisabledAlerts(Map args=[:]) {
    assert args?.silenceID : "getDisabledAlerts error: missing silenceID argument. Arguments: ${args}"

    String strResponse = sh(label: "HTTP Prometheus API: get silence",
        returnStdout: true,
        script: """#!/bin/bash -e
            curl -X GET \
                https://prom.ops.vega.xyz/alertmanager/api/v2/silence/${args.silenceID}
        """
    ).trim()

    print("getDisabledAlerts response: ${strResponse}")

    def response = new groovy.json.JsonSlurperClassic().parseText(strResponse)
    return response
}
