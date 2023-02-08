import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder

String createSilence(Map args=[:]) {
    String matcherName = ""
    String matcherValue = ""

    if (args?.node) {
        matcherName = "instance"
        matcherValue = args.node
    } else if (args?.environment) {
        matcherName = "environment"
        matcherValue = args.environment
    } else {
        throw "createSilence error: need to provide 'node' or 'env' argument. Provided: ${args}"
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
                    "comment": "Created by Jenkins pipeline: ${env.JOB_URL}",
                    "id": null
                }'
        """
    ).trim()

    print("createSilence response: ${strResponse}")

    def response = new groovy.json.JsonSlurperClassic().parseText(strResponse)
    def silenceID = response["silenceID"]

    print("Silenced alerts for ${matcherName}=${matcherValue} for ${duration} minutes. Silence ID: ${silenceID}")

    return silenceID
}

void deleteSilence(Map args=[:]) {
    assert args?.silenceID : "createSilence error: missing silenceID argument. Arguments: ${args}"
    int delay = (args?.delay ?: "5") as int // in minutes

    def now = new Date()
    def end = new Date(now.getTime() + (args.delay * 60 * 1000))
    String strEnd = end.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))

    silenceConfig = getSilence(silenceID: args.silenceID)
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

    print("Silence for alert ${matcherName}=${matcherValue} will be disabled in ${args.delay} minutes. Silence ID: ${args.silenceID}")
}

Object getSilence(Map args=[:]) {
    assert args?.silenceID : "getSilence error: missing silenceID argument. Arguments: ${args}"

    String strResponse = sh(label: "HTTP Prometheus API: get silence",
        returnStdout: true,
        script: """#!/bin/bash -e
            curl -X GET \
                https://prom.ops.vega.xyz/alertmanager/api/v2/silence/${args.silenceID}
        """
    ).trim()

    print("getSilence response: ${strResponse}")

    def response = new groovy.json.JsonSlurperClassic().parseText(strResponse)
    return response
}
