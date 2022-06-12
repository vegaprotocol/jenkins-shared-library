/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(Map config=[:]) {

    String vegaNetwork = config.network ?: 'devnet1'
    Map<String, List<String>> serversByNetwork = [
        'devnet1': (1..4).collect { "n0${it}.d.vega.xyz" },
        'stagnet1': (1..5).collect { "n0${it}.s.vega.xyz" },
        'stagnet2': (1..5).collect { "n0${it}.stagnet2.vega.xyz" } + (1..5).collect { "v0${it}.stagnet2.vega.xyz" },
        'stagnet3': (1..9).collect { "n0${it}.stagnet3.vega.xyz" },
        'fairground': (1..9).collect { "n0${it}.testnet.vega.xyz" },
    ]
    List<String> vegaNetworkList = new ArrayList<String>()
    vegaNetworkList.addAll(serversByNetwork.keySet())

    if (vegaNetwork in vegaNetworkList) {
        // move `vegaNetwork` to the beggining of the list
        vegaNetworkList = [vegaNetwork] + (vegaNetworkList - vegaNetwork)
    } else {
        error("Unknown network ${vegaNetwork}. Allowed values: ${vegaNetworkList}")
    }

    String defaultTimeout = config.timeout ?: '10'
    // usually setup+download etc takes 20-100sec
    String cronConfig = "H/${defaultTimeout.toInteger() + 2} * * * *"

    properties([
        buildDiscarder(logRotator(daysToKeepStr: '14')),
        copyArtifactPermission('*'),
        pipelineTriggers([cron(cronConfig)]),
        parameters([
            choice(
                name: 'NETWORK', choices: vegaNetworkList, // defaultValue is the first from the list
                description: 'Vega Network to connect to'),
            string(
                name: 'TIMEOUT', defaultValue: defaultTimeout,
                description: 'Number of minutes after which the node will stop'),
        ])
    ])

    echo "params=${params}"

    node('non-validator') {
        /* groovylint-disable-next-line NoDef, VariableTypeRequired */
        def sshDevnetCredentials = sshUserPrivateKey(  credentialsId: 'ssh-vega-network',
                                                     keyFileVariable: 'PSSH_KEYFILE',
                                                    usernameVariable: 'PSSH_USER')
        skipDefaultCheckout()
        cleanWs()
        String remoteServer
        String jenkinsAgentPublicIP
        def TM_VERSION
        def TRUST_HASH
        def TRUST_HEIGHT
        def RPC_SERVERS
        def PERSISTENT_PEERS

        def blockHeightStart
        def blockHeightEnd

        // Checks
        boolean chainStatusConnected = false

        timestamps {
            try {
                // give extra 5 minutes for setup
                timeout(time: params.TIMEOUT.toInteger() + 5, unit: 'MINUTES') {
                    stage('CI config') {
                        // Printout all configuration variables
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                        jenkinsAgentPublicIP = sh(
                            script: 'curl --max-time 3 http://169.254.169.254/latest/meta-data/public-ipv4',
                            returnStdout: true,
                        ).trim()
                        echo "jenkinsAgentPublicIP=${jenkinsAgentPublicIP}"
                    }

                    stage('Find available remote server') {
                        List<String> networkServers = serversByNetwork[params.NETWORK].clone()
                        // Randomize order
                        // workaround to .shuffled() not implemented
                        Random random = new Random();  
                        for(int index = 0; index < networkServers.size(); index += 1) {  
                            Collections.swap(networkServers, index, index + random.nextInt(networkServers.size() - index));  
                        }
                        echo "Going to check servers: ${networkServers}"
                        for(String server in networkServers) { 
                            if (isRemoteServerAlive(server)) {
                                remoteServer = server
                                break
                            }
                        }
                        echo "Found available server: ${remoteServer}"
                    }

                    if ( remoteServer == null ) {
                        currentBuild.result = 'SUCCESS'
                        // return outside of Stage stops the whole pipeline
                        return
                    }

                    stage('Download') {
                        parallel([
                            'dependencies': {
                                sh label: 'download dasel to edit toml files',
                                    script: '''#!/bin/bash -e
                                        wget https://github.com/TomWright/dasel/releases/download/v1.24.3/dasel_linux_amd64 && \
                                            mv dasel_linux_amd64 dasel && \
                                            chmod +x dasel
                                    '''
                            },
                            'vega core binary': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp vega core from ${remoteServer}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServer}\":/home/vega/current/vega vega
                                        """
                                }
                            },
                            'genesis.json': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp genesis.json from ${remoteServer}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServer}\":/home/vega/.tendermint/config/genesis.json genesis.json
                                        """
                                }
                            }
                        ])
                    }

                    stage("Initialize configs") {
                        sh label: 'vega init + copy genesis.json',
                            script: '''#!/bin/bash -e
                                ./vega init full --home=./vega_config --output=json &&./vega tm init full --home=./tm_config
                                cp genesis.json ./tm_config/config/genesis.json
                            '''
                    }

                    stage("Get Tendermint config") {
                        // Check TM version
                        def status_req = new URL("https://${remoteServer}/tm/status").openConnection();
                        def status = new groovy.json.JsonSlurper().parseText(status_req.getInputStream().getText())
                        TM_VERSION = status.result.node_info.version
                        if(TM_VERSION.startsWith("0.34")) {
                            def net_info_req = new URL("https://${remoteServer}/tm/net_info").openConnection();
                            def net_info = new groovy.json.JsonSlurper().parseText(net_info_req.getInputStream().getText())
                            RPC_SERVERS = net_info.result.peers*.node_info.listen_addr.collect{addr -> addr.replaceAll(/26656/, "26657")}.join(",")
                            PERSISTENT_PEERS = net_info.result.peers*.node_info.collect{node -> node.id + "@" + node.listen_addr}.join(",")
                        } else {
                            def net_info_req = new URL("https://${remoteServer}/tm/net_info").openConnection();
                            def net_info = new groovy.json.JsonSlurper().parseText(net_info_req.getInputStream().getText())
                            def servers_with_id = net_info.result.peers*.url.collect{url -> url.replaceAll(/mconn.*\/(.*):.*/, "\$1")}
                            RPC_SERVERS = servers_with_id.collect{server -> server.split('@')[1] + ":26657"}.join(",")
                            PERSISTENT_PEERS = servers_with_id.collect{peer -> peer + ":26656"}.join(",")
                        }
                        

                        // Get trust block info
                        def block_req = new URL("https://${remoteServer}/tm/block").openConnection();
                        def tm_block = new groovy.json.JsonSlurper().parseText(block_req.getInputStream().getText())
                        TRUST_HASH = tm_block.result.block_id.hash
                        TRUST_HEIGHT = tm_block.result.block.header.height
                        println("RPC_SERVERS=${RPC_SERVERS}")
                        println("PERSISTENT_PEERS=${PERSISTENT_PEERS}")
                        println("TRUST_HASH=${TRUST_HASH}")
                        println("TRUST_HEIGHT=${TRUST_HEIGHT}")
                    }

                    if(TM_VERSION.startsWith("0.34")) {
                        stage("Set Tendermint config") {
                            sh label: 'set Tendermint config v.34.x',
                                script: """#!/bin/bash -e
                                    ./dasel put bool -f tm_config/config/config.toml statesync.enable true
                                    ./dasel put string -f tm_config/config/config.toml statesync.trust_hash ${TRUST_HASH}
                                    ./dasel put string -f tm_config/config/config.toml statesync.trust_height ${TRUST_HEIGHT}
                                    ./dasel put string -f tm_config/config/config.toml statesync.rpc_servers ${RPC_SERVERS}
                                    ./dasel put string -f tm_config/config/config.toml p2p.persistent_peers ${PERSISTENT_PEERS}
                                    ./dasel put string -f tm_config/config/config.toml p2p.max_packet_msg_payload_size 7024
                                    ./dasel put string -f tm_config/config/config.toml p2p.external_address "${jenkinsAgentPublicIP}:26656"
                                    cat tm_config/config/config.toml
                                """
                        }
                    } else {
                        stage("Set Tendermint config") {
                            sh label: 'set Tendermint config v.35.x',
                                script: """#!/bin/bash -e
                                    ./dasel put bool -f tm_config/config/config.toml statesync.enable true
                                    ./dasel put string -f tm_config/config/config.toml statesync.trust-hash ${TRUST_HASH}
                                    ./dasel put string -f tm_config/config/config.toml statesync.trust-height ${TRUST_HEIGHT}
                                    ./dasel put string -f tm_config/config/config.toml statesync.rpc-servers ${RPC_SERVERS}
                                    ./dasel put string -f tm_config/config/config.toml p2p.persistent-peers ${PERSISTENT_PEERS}
                                    ./dasel put string -f tm_config/config/config.toml p2p.max-packet-msg-payload-size 7024
                                    ./dasel put string -f tm_config/config/config.toml p2p.external-address "${jenkinsAgentPublicIP}:26656"
                                    cat tm_config/config/config.toml
                                """
                        }
                    }

                    stage('Run') {
                        parallel(
                            failFast: true,
                            'Vega': {
                                boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                    sh label: 'Start vega node',
                                        script: """#!/bin/bash -e
                                            ./vega node --home=vega_config \
                                                --processor.log-level=debug \
                                                --snapshot.log-level=debug
                                        """
                                }
                                if ( !nice && isRemoteServerAlive(remoteServer) ) {
                                    error("Vega stopped too early, Remote Server is still alive.")
                                }
                            },
                            'Tendermint': {
                                boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                    sh label: 'Start tendermint',
                                        script: """#!/bin/bash -e
                                            ./vega tm start --home=tm_config
                                        """
                                }
                                if ( !nice && isRemoteServerAlive(remoteServer) ) {
                                    error("Vega stopped too early, Remote Server is still alive.")
                                }
                            },
                            'Checks': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    // run at 50sec, 1min50sec, 2min50sec, ... since start
                                    int runEveryMs = 60 * 1000
                                    int startAt = currentBuild.duration
                                    while (true) {
                                        // wait until next Xmin50sec
                                        int sleepForMs = runEveryMs - ((currentBuild.duration - startAt + 10 * 1000) % runEveryMs)
                                        sleep(time:sleepForMs, unit:'MILLISECONDS')

                                        String sinceStartSec = Math.round((currentBuild.duration - startAt)/1000)
                                        sh label: "Get non-validator statistics (${sinceStartSec} sec)", script: """#!/bin/bash -e
                                            curl --max-time 5 http://127.0.0.1:3003/statistics
                                        """
                                        sinceStartSec = Math.round((currentBuild.duration - startAt)/1000)
                                        sh label: "Get ${remoteServer} statistics (${sinceStartSec} sec)",
                                            returnStatus: true,  // ignore exit code
                                            script: """#!/bin/bash -e
                                                curl --max-time 5 https://${remoteServer}/statistics
                                            """

                                        if (!chainStatusConnected) {
                                            String chainStatus = sh(
                                                script: 'curl --max-time 5 --silent http://127.0.0.1:3003/statistics | jq -r .statistics.status',
                                                returnStdout: true,
                                            ).trim()
                                            if (chainStatus == "CHAIN_STATUS_CONNECTED") {
                                                chainStatusConnected = true
                                                echo "Marked CHAIN_STATUS_CONNECTED !!"
                                            }
                                        }
                                    }
                                }
                            },
                            'Info': {
                                echo "Jenkins Agent Public IP: ${jenkinsAgentPublicIP}. Some useful links:"
                                echo "http://${jenkinsAgentPublicIP}:3003/statistics"
                                echo "https://${remoteServer}/statistics"
                                echo "https://${remoteServer}/tm/net_info"
                            }
                        )
                    }
                    stage("Verify checks") {
                        if (!chainStatusConnected) {
                            error("Non-validator never reached CHAIN_STATUS_CONNECTED status.")
                        }
                    }
                }
                currentBuild.result = 'SUCCESS'
            } catch (FlowInterruptedException e) {
                currentBuild.result = 'ABORTED'
                throw e
            } catch (e) {
                currentBuild.result = 'FAILURE'
                throw e
            } finally {
                stage('Notification') {
                    sendSlackMessage(params.NETWORK)
                }
            }
        }
    }
}

boolean nicelyStopAfter(String timeoutMin, Closure body) {
    int startTimeMs = currentBuild.duration
    catchError(
        message: "Timed task",
        buildResult: 'SUCCESS', // don't modify Build Status
        stageResult: 'SUCCESS', // keep Stage status Successful
        catchInterruptions: true, // timeout is FlowInterruptedException
    ) {
        timeout(time: timeoutMin, unit: 'MINUTES') {
            try {
                body()
            } catch (FlowInterruptedException e) {
                currentBuild.result = "SUCCESS"
            }
        }
    }
    return ( timeoutMin.toInteger() * 60 - 5 ) * 1000 < (currentBuild.duration - startTimeMs)
}

boolean isRemoteServerAlive(String remoteServer) {
    try {
        def statistics_req = new URL("https://${remoteServer}/statistics").openConnection()
        statistics_req.setConnectTimeout(5000)
        statistics_req.getInputStream().getText()
        return true
    } catch (IOException e) {
        return false
    }
}

void sendSlackMessage(String vegaNetwork) {
    String slackChannel = '#monitoring'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.displayName

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    if (currentResult == 'SUCCESS') {
        
        msg = ":large_green_circle: Snapshot testing (${vegaNetwork}) - SUCCESS - <${jobURL}|${jobName}>"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: Snapshot testing (${vegaNetwork}) - ABORTED - <${jobURL}|${jobName}>"
        color = '#000000'
    } else {
        msg = ":red_circle: Snapshot testing (${vegaNetwork}) - FAILED - <${jobURL}|${jobName}>"
        color = 'danger'
    }

    msg += " (${duration})"

    echo "${msg}"

    // slackSend(
    //     channel: slackChannel,
    //     color: color,
    //     message: msg,
    // )
}
