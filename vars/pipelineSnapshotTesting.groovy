/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(Map config=[:]) {

    String vegaNetwork = config.network ?: 'devnet1'
    // todo: checkout ansible repo and read inventory
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

        // Checks
        String extraMsg = null  // extra message to print on Slack. In case of multiple message, keep only first.
        String catchupTime = null
        boolean chainStatusConnected = false
        boolean caughtUp = false
        boolean blockHeightIncreased = false

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
                        List<String> networkServers = serversByNetwork[env.NETWORK].clone()
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
                        if ( remoteServer == null ) {
                            // No single machine online means that Vega Network is down
                            // This is quite often for Devnet, when deployments happen all the time
                            extraMsg = extraMsg ?: "${env.NETWORK} seems down. Snapshot test aborted."
                            currentBuild.result = 'ABORTED'
                            error("${env.NETWORK} seems down")
                        }
                        echo "Found available server: ${remoteServer}"
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
                                    sh label: "vega version", script: """#!/bin/bash -e
                                        ./vega version
                                    """
                                }
                            },
                            'genesis.json': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp genesis.json from ${remoteServer}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServer}\":/home/vega/.tendermint/config/genesis.json genesis.json
                                        """
                                    sh label: "print genesis.json", script: """#!/bin/bash -e
                                        cat genesis.json
                                    """
                                }
                            }
                        ])
                    }

                    stage("Initialize configs") {
                        sh label: 'vega init + copy genesis.json',
                            script: '''#!/bin/bash -e
                                ./vega init full --home=./vega_config --output=json && ./vega tm init full --home=./tm_config
                                cp genesis.json ./tm_config/config/genesis.json
                            '''
                    }

                    stage("Get Tendermint config") {
                        try {
                            // Check TM version
                            def status_req = new URL("https://${remoteServer}/tm/status").openConnection();
                            def status = new groovy.json.JsonSlurperClassic().parseText(status_req.getInputStream().getText())
                            TM_VERSION = status.result.node_info.version

                            // Get data from TM
                            if(TM_VERSION.startsWith("0.34")) {
                                def net_info_req = new URL("https://${remoteServer}/tm/net_info").openConnection();
                                def net_info = new groovy.json.JsonSlurperClassic().parseText(net_info_req.getInputStream().getText())
                                RPC_SERVERS = net_info.result.peers*.node_info.listen_addr.collect{addr -> addr.replaceAll(/26656/, "26657")}.join(",")
                                PERSISTENT_PEERS = net_info.result.peers*.node_info.collect{node -> node.id + "@" + node.listen_addr}.join(",")
                            } else {
                                def net_info_req = new URL("https://${remoteServer}/tm/net_info").openConnection();
                                def net_info = new groovy.json.JsonSlurperClassic().parseText(net_info_req.getInputStream().getText())
                                def servers_with_id = net_info.result.peers*.url.collect{url -> url.replaceAll(/mconn.*\/(.*):.*/, "\$1")}
                                RPC_SERVERS = servers_with_id.collect{server -> server.split('@')[1] + ":26657"}.join(",")
                                PERSISTENT_PEERS = servers_with_id.collect{peer -> peer + ":26656"}.join(",")
                            }


                            // Get trust block info
                            def block_req = new URL("https://${remoteServer}/tm/block").openConnection();
                            def tm_block = new groovy.json.JsonSlurperClassic().parseText(block_req.getInputStream().getText())
                            TRUST_HASH = tm_block.result.block_id.hash
                            TRUST_HEIGHT = tm_block.result.block.header.height
                            println("RPC_SERVERS=${RPC_SERVERS}")
                            println("PERSISTENT_PEERS=${PERSISTENT_PEERS}")
                            println("TRUST_HASH=${TRUST_HASH}")
                            println("TRUST_HEIGHT=${TRUST_HEIGHT}")
                        } catch (e) {
                            if ( !isRemoteServerAlive(remoteServer) ) {
                                // Remote server stopped being available.
                                // This is quite often for Devnet, when deployments happen all the time
                                extraMsg = extraMsg ?: "${env.NETWORK} seems down. Snapshot test aborted."
                                currentBuild.result = 'ABORTED'
                                error("${env.NETWORK} seems down")
                            } else {
                                println("Remote server ${remoteServer} is still up.")
                                // re-throw
                                throw e
                            }
                        }
                    }

                    if(TM_VERSION.startsWith("0.34")) {
                        stage("Set Tendermint config") {
                            sh label: 'set Tendermint config v.34.x',
                                script: """#!/bin/bash -e
                                    ./dasel put bool -f tm_config/config/config.toml statesync.enable true
                                    ./dasel put string -f tm_config/config/config.toml statesync.trust_hash ${TRUST_HASH}
                                    ./dasel put int -f tm_config/config/config.toml statesync.trust_height ${TRUST_HEIGHT}
                                    ./dasel put string -f tm_config/config/config.toml statesync.rpc_servers ${RPC_SERVERS}
                                    ./dasel put string -f tm_config/config/config.toml statesync.discovery_time "30s"
                                    ./dasel put string -f tm_config/config/config.toml statesync.chunk_request_timeout "30s"
                                    ./dasel put string -f tm_config/config/config.toml p2p.persistent_peers ${PERSISTENT_PEERS}
                                    ./dasel put string -f tm_config/config/config.toml p2p.seeds ${PERSISTENT_PEERS}
                                    ./dasel put int -f tm_config/config/config.toml p2p.max_packet_msg_payload_size 10240
                                    ./dasel put string -f tm_config/config/config.toml p2p.external_address "${jenkinsAgentPublicIP}:26656"
                                    ./dasel put bool -f tm_config/config/config.toml p2p.allow_duplicate_ip true
                                    cat tm_config/config/config.toml
                                """
                        }
                    } else {
                        stage("Set Tendermint config") {
                            sh label: 'set Tendermint config v.35.x',
                                script: """#!/bin/bash -e
                                    ./dasel put bool -f tm_config/config/config.toml statesync.enable true
                                    ./dasel put string -f tm_config/config/config.toml statesync.trust-hash ${TRUST_HASH}
                                    ./dasel put int -f tm_config/config/config.toml statesync.trust-height ${TRUST_HEIGHT}
                                    ./dasel put string -f tm_config/config/config.toml statesync.rpc-servers ${RPC_SERVERS}
                                    ./dasel put string -f tm_config/config/config.toml statesync.discovery-time "30s"
                                    ./dasel put string -f tm_config/config/config.toml statesync.chunk-request-timeout "30s"
                                    ./dasel put string -f tm_config/config/config.toml p2p.persistent-peers ${PERSISTENT_PEERS}
                                    ./dasel put string -f tm_config/config/config.toml p2p.bootstrap-peers ${PERSISTENT_PEERS}
                                    ./dasel put int -f tm_config/config/config.toml p2p.max-packet-msg-payload-size 10240
                                    ./dasel put string -f tm_config/config/config.toml p2p.external-address "${jenkinsAgentPublicIP}:26656"
                                    ./dasel put bool -f tm_config/config/config.toml p2p.allow-duplicate-ip true
                                    cat tm_config/config/config.toml
                                """
                        }
                    }

                    stage('Run') {
                        parallel(
                            failFast: true,
                            'Vega': {
                                boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                    if (env.DISABLE_TENDERMINT) {
                                        sh label: 'Start vega node',
                                            script: """#!/bin/bash -e
                                                ./vega start --home=vega_config \
                                                    --tendermint-home=tm_config \
                                                    --snapshot.log-level=debug
                                            """
                                    } else {
                                        sh label: 'Start vega node',
                                            script: """#!/bin/bash -e
                                                ./vega node --home=vega_config \
                                                    --snapshot.log-level=debug
                                            """
                                    }
                                }
                                if ( !nice && isRemoteServerAlive(remoteServer) ) {
                                    extraMsg = extraMsg ?: "Vega core stopped too early."
                                    error("Vega stopped too early, Remote Server is still alive.")
                                }
                            },
                            'Tendermint': {
                                if (!env.DISABLE_TENDERMINT) {
                                    boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                        sh label: 'Start tendermint',
                                            script: """#!/bin/bash -e
                                                ./vega tm start --home=tm_config
                                            """
                                    }
                                    if ( !nice && isRemoteServerAlive(remoteServer) ) {
                                        extraMsg = extraMsg ?: "Tendermint stopped too early."
                                        error("Tendermint stopped too early, Remote Server is still alive.")
                                    }
                                } else {
                                    echo "tendermint is embedded into vega right now"
                                }
                            },
                            'Checks': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    // run at 20sec, 50sec, 1min20sec, 1min50sec, 2min20sec, ... since start
                                    int runEverySec = 30
                                    int runEveryMs = runEverySec * 1000
                                    int startAt = currentBuild.duration
                                    int previousLocalHeight = -1
                                    String currTime = currentBuild.durationString - ' and counting'
                                    println("Checks are run every ${runEverySec} seconds (${currTime})")
                                    while (true) {
                                        // wait until next 20 or 50 sec past full minute since start
                                        int sleepForMs = runEveryMs - ((currentBuild.duration - startAt + 10 * 1000) % runEveryMs)
                                        sleep(time:sleepForMs, unit:'MILLISECONDS')

                                        String timeSinceStartSec = Math.round((currentBuild.duration - startAt)/1000)

                                        String remoteServerStats = sh(
                                                script: "curl --max-time 5 https://${remoteServer}/statistics",
                                                returnStdout: true,
                                            ).trim()
                                        println("https://${remoteServer}/statistics\n${remoteServerStats}")
                                        Object remoteStats = new groovy.json.JsonSlurperClassic().parseText(remoteServerStats)
                                        String localServerStats = sh(
                                                script: "curl --max-time 5 http://127.0.0.1:3003/statistics",
                                                returnStdout: true,
                                            ).trim()
                                        println("http://127.0.0.1:3003/statistics\n${localServerStats}")
                                        Object localStats = new groovy.json.JsonSlurperClassic().parseText(localServerStats)

                                        if (!chainStatusConnected) {
                                            if (localStats.statistics.status == "CHAIN_STATUS_CONNECTED") {
                                                chainStatusConnected = true
                                                currTime = currentBuild.durationString - ' and counting'
                                                println("Node has reached status CHAIN_STATUS_CONNECTED !! (${currTime})")
                                            }
                                        }
                                        if (chainStatusConnected) {
                                            int remoteHeight = remoteStats.statistics.blockHeight.toInteger()
                                            int localHeight = localStats.statistics.blockHeight.toInteger()

                                            if (!blockHeightIncreased) {
                                                if (localHeight > 0) {
                                                    if (previousLocalHeight < 0) {
                                                        previousLocalHeight = localHeight
                                                    } else if (localHeight > previousLocalHeight) {
                                                        blockHeightIncreased = true
                                                        currTime = currentBuild.durationString - ' and counting'
                                                        println("Detected that block has increased from ${previousLocalHeight} to ${localHeight} (${currTime})")
                                                    }
                                                }
                                            }

                                            if (!caughtUp && (remoteHeight - localHeight < 10)) {
                                                caughtUp = true
                                                catchupTime = currentBuild.durationString - ' and counting'
                                                println("Node has caught up with the vega network !! (heights local: ${localHeight}, remote: ${remoteHeight}) (${catchupTime})")
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
                            extraMsg = extraMsg ?: "Not reached CHAIN_STATUS_CONNECTED."
                            error("Non-validator never reached CHAIN_STATUS_CONNECTED status.")
                        }
                        if (!blockHeightIncreased) {
                            extraMsg = extraMsg ?: "block height didn't increase."
                            error("Non-validator block height did not incrase.")
                        }
                        if (!caughtUp) {
                            extraMsg = extraMsg ?: "didn't catch up with network."
                            error("Non-validator did not catch up.")
                        }
                        println("All checks passed.")
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
                    sendSlackMessage(env.NETWORK, extraMsg, catchupTime)
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

void sendSlackMessage(String vegaNetwork, String extraMsg, String catchupTime) {
    String slackChannel = '#snapshot-notify'
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

    if (catchupTime != null) {
        msg += " (catch up in ${catchupTime})"
    }

    if (extraMsg != null) {
        msg += " (reason: ${extraMsg})"
    }

    msg += " (${duration})"

    echo "${msg}"

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )
}
