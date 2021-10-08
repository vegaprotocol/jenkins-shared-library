/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable UnnecessaryGetter */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import io.vegaprotocol.DockerisedVega

void call() {
    echo "buildCauses=${currentBuild.buildCauses}"
    if (currentBuild.upstreamBuilds) {
        RunWrapper upBuild = currentBuild.upstreamBuilds[0]
        currentBuild.displayName = "#${currentBuild.id} - ${upBuild.fullProjectName} #${upBuild.id}"
    }
    pipelineDockerisedVega.call([
        parameters: [
            string(
                name: 'NAME', defaultValue: '',
                description: 'Network name - used only to display on Jenkins'),
            string(
                name: 'TIMEOUT', defaultValue: '200',
                description: 'Timeout after which the network will be stopped. Default 200min'),
            string(
                name: 'JENKINS_AGENT_LABEL', defaultValue: 'private-network',
                description: 'Specify Jenkins machine on which to run this pipeline')
        ],
        prepareStages: [
            'net': { Map vars ->
                stage('Set name') {
                    String whoStarted = currentBuild.getBuildCauses()[0].shortDescription - 'Started by user '
                    String networkName = params.NAME ?: whoStarted
                    currentBuild.displayName = "${networkName} #${currentBuild.id}"
                }
            }
        ],

        mainStage: { Map vars ->
            DockerisedVega dockerisedVega = vars.dockerisedVega
            String ip = vars.jenkinsAgentPublicIP
            Map<String,String> usefulLinks = dockerisedVega.getUsefulLinks(ip)
            Map<String,Map<String,String>> endpointInfo = dockerisedVega.getEndpointInformation(ip)
            List parameters = []

            usefulLinks.eachWithIndex { linkName, linkURL, index ->
                if (index == 0) {
                    parameters << booleanParam(description: 'Useful links', name: "[${linkName}]: ${linkURL}")
                } else {
                    parameters << booleanParam(name: "[${linkName}]: ${linkURL}")
                }
            }

            endpointInfo.each { machine, endpoints ->
                endpoints.eachWithIndex { endpointType, endpoint, index ->
                    if (index == 0) {
                        parameters << booleanParam(description: machine, name: "[${endpointType}]: ${endpoint}")
                    } else {
                        parameters << booleanParam(name: "[${endpointType}]: ${endpoint}")
                    }
                }
            }

            timeout(time: params.TIMEOUT as int, unit: 'MINUTES') {
                input message: 'Private network is ready', ok: 'Stop network',
                    parameters: parameters
            }
        }
    ])
}
