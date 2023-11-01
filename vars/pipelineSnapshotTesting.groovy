/* groovylint-disable DuplicateStringLiteral, ImplicitClosureParameter, LineLength */
/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import groovy.time.TimeCategory

void call(Map config=[:]) {
    echo "params=${params}"

    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshDevnetCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    String remoteServer
    String jenkinsAgentIP
    String monitoringDashboardURL

    // api output placeholders
    def TM_VERSION
    def RPC_SERVERS
    def SEEDS
    def SNAPSHOT_HEIGHT
    def SNAPSHOT_HASH
    def NETWORK_HISTORY_PEERS

    // Checks
    String extraMsg = null  // extra message to print on Slack. In case of multiple message, keep only first.
    String catchupTime = null
    boolean chainStatusConnected = false
    boolean caughtUp = false
    boolean blockHeightIncreased = false
    int notHealthyAgainCount = 0
    String networkVersion = null
    String devopsToolsBranch = params.DEVOPSTOOLS_BRANCH ?: 'main'

    Map<String, List<String>> healthyNodes = [:]
    List<String> networkNodes = []
    List<String> tendermintNodes = []

    String remoteServerDataNode = ""
    String remoteServerCometBFT = ""

    node(params.NODE_LABEL) {
        timestamps {

            try {

                stage('init') {
                    skipDefaultCheckout()
                    cleanWs()
                    script {
                        // initial cleanup
                        vegautils.commonCleanup()
                        // init global variables
                        monitoringDashboardURL = jenkinsutils.getMonitoringDashboardURL([job: "snapshot-${env.NET_NAME}"])
                        jenkinsAgentIP = agent.getPublicIP()
                        echo "Jenkins Agent IP: ${jenkinsAgentIP}"
                        echo "Monitoring Dahsboard: ${monitoringDashboardURL}"
                        // set job Title and Description
                        String prefixDescription = jenkinsutils.getNicePrefixForJobDescription()
                        currentBuild.displayName = "#${currentBuild.id} ${prefixDescription} [${env.NODE_NAME.take(12)}]"
                        currentBuild.description = "Monitoring: ${monitoringDashboardURL}, Jenkins Agent IP: ${jenkinsAgentIP} [${env.NODE_NAME}]"
                        // Setup grafana-agent
                        grafanaAgent.configure("snapshot", [
                            JENKINS_JOB_NAME: "snapshot-${env.NET_NAME}",
                        ])
                        grafanaAgent.restart()
                    }
                }

                stage('INFO') {
                    // Print Info only, do not execute anythig
                    echo "Jenkins Agent IP: ${jenkinsAgentIP}"
                    echo "Jenkins Agent name: ${env.NODE_NAME}"
                    echo "Monitoring Dahsboard: ${monitoringDashboardURL}"
                    echo "Core stats: http://${jenkinsAgentIP}:3003/statistics"
                    echo "GraphQL: http://${jenkinsAgentIP}:3008/graphql/"
                    echo "Epoch: http://${jenkinsAgentIP}:3008/api/v2/epoch"
                    echo "Data-Node stats: http://${jenkinsAgentIP}:3008/statistics"
                    echo "External Data-Node stats: https://${remoteServerDataNode}/statistics"
                    echo "CometBFT: ${remoteServerCometBFT}/net_info"
                }

                // give extra 12 minutes for setup
                timeout(time: params.TIMEOUT.toInteger() + 12, unit: 'MINUTES') {
                    stage('Clone devopstools') {
                        gitClone([
                            url: 'git@github.com:vegaprotocol/devopstools.git',
                            branch: devopsToolsBranch,
                            credentialsId: 'vega-ci-bot',
                            directory: 'devopstools'
                        ])
                    }

                    stage('Find available remote server') {

                        if (env.NET_NAME == "fairground") {
                            baseDomain = "testnet.vega.rocks"
                        }


                        /**
                         * Return the following structure:
                         * {
                         *   "validators": [ .... ],
                         *   "explorers": [ .... ],
                         *   "data_nodes": [ .... ],
                         *   "all": [ .... ]
                         * }
                         */
                        String healthyNodesJSON = withDevopstools(
                            command: 'network healthy-nodes',
                            netName: env.NET_NAME,
                            returnStdout: true,
                        )

                        print('Found healthy servers: \n' + healthyNodesJSON)

                        healthyNodes = readJSON text: healthyNodesJSON
                        if (healthyNodes["data_nodes"].size() < 1) {
                            currentBuild.result = 'ABORTED'
                            error("No healthy data nodes")
                            extraMsg = extraMsg ?: "${env.NET_NAME} seems down. Snapshot test aborted."
                        }
                        networkNodes = healthyNodes["data_nodes"]
                        tendermintNodes = healthyNodes["tendermint_endpoints"]

                        if (tendermintNodes.size() < 1) {
                            currentBuild.result = 'ABORTED'
                            error("No healthy tendermint nodes")
                            extraMsg = extraMsg ?: "${env.NET_NAME} seems down. Snapshot test aborted."
                        }

                        // exclude from checking servers that are in the denylist configured in DSL
                        if (env.NODES_DENYLIST) {
                            def denyList = (env.NODES_DENYLIST as String).split(',')
                            networkNodes = networkNodes.findAll{ server -> !denyList.contains(server) }
                        }

                        Collections.shuffle(networkNodes)

                        remoteServerDataNode = networkNodes[0]
                        remoteServerCometBFT = tendermintNodes[0]
                        if (!remoteServerCometBFT.contains("http")) {
                            remoteServerCometBFT = 'https://' + remoteServerCometBFT
                        }


                        echo "Found available server: ${remoteServerDataNode} (${remoteServerCometBFT})"
                    }

                    stage('Download') {
                        parallel([
                            // TODO: add to jenkins-agnet build
                            'dasel & data node config': {
                                sh label: 'download dasel to edit toml files',
                                    script: '''#!/bin/bash -e
                                        wget --no-verbose https://github.com/TomWright/dasel/releases/download/v1.24.3/dasel_linux_amd64 && \
                                            mv dasel_linux_amd64 dasel && \
                                            chmod +x dasel
                                    '''
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp data node config from ${remoteServerDataNode}",
                                        script: """#!/bin/bash -e
                                            scp -o "UserKnownHostsFile=/dev/null" -o "StrictHostKeyChecking=no" -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServerDataNode}\":/home/vega/vega_home/config/data-node/config.toml data-node-config.toml
                                        """
                                }
                                NETWORK_HISTORY_PEERS = sh(
                                    label: 'read persistent peers',
                                    script: "./dasel -f data-node-config.toml -w json -c ${env.HISTORY_KEY}.Store.BootstrapPeers",
                                    returnStdout: true
                                ).trim()
                                echo "NETWORK_HISTORY_PEERS=${NETWORK_HISTORY_PEERS}"
                            },
                            'vega core binary': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "rsync vega core from ${remoteServerDataNode}",
                                        script: """#!/bin/bash -e
                                            time rsync -avz \
                                                -e "ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i '\${PSSH_KEYFILE}'" \
                                                --progress \
                                                \"\${PSSH_USER}\"@'${remoteServerDataNode}':/home/vega/vegavisor_home/current/vega \
                                                ./vega
                                        """
                                    sh label: "vega version", script: """#!/bin/bash -e
                                        ./vega version
                                    """
                                }
                            },
                            'genesis.json': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp genesis.json from ${remoteServerDataNode}",
                                        script: """#!/bin/bash -e
                                            scp  -o "UserKnownHostsFile=/dev/null" -o "StrictHostKeyChecking=no" -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServerDataNode}\":/home/vega/tendermint_home/config/genesis.json genesis.json
                                        """
                                    // sh label: "print genesis.json", script: """#!/bin/bash -e
                                    //     cat genesis.json
                                    // """
                                }
                            }
                        ])
                    }

                    stage("Initialize configs") {
                        sh label: 'vega init + copy genesis.json',
                            script: '''#!/bin/bash -e
                                ./vega init full --home=./vega_config --output=json
                                ./vega tm init full --home=./tm_config
                                ./vega datanode init --home=./vega_config $(./dasel -f genesis.json -r json chain_id | sed 's|"||g')
                                cp genesis.json ./tm_config/config/genesis.json
                            '''
                    }

                    stage("Get API data related to configs") {
                        // mitigiate moment when network restarts
                        retry(3) {
                            try {
                                // Check last snapshoted block
                                def snapshot_req = new URL("https://${remoteServerDataNode}/api/v2/snapshots").openConnection()
                                def snapshot = new groovy.json.JsonSlurperClassic().parseText(snapshot_req.getInputStream().getText())
                                def snapshotInfo = snapshot['coreSnapshots']['edges'][0]['node']
                                SNAPSHOT_HEIGHT = snapshotInfo['blockHeight']
                                SNAPSHOT_HASH = snapshotInfo['blockHash']
                                println("SNAPSHOT_HEIGHT='${SNAPSHOT_HEIGHT}' - also used as trusted block height in tendermint statesync config")
                                println("SNAPSHOT_HASH='${SNAPSHOT_HASH}' - also used as trusted block hash in tendermint statesync config")
                                currentBuild.description += " block: ${SNAPSHOT_HEIGHT}"

                                // Check TM version
                                def status_req = new URL("${remoteServerCometBFT}/status").openConnection()
                                def status = new groovy.json.JsonSlurperClassic().parseText(status_req.getInputStream().getText())
                                TM_VERSION = status.result.node_info.version
                                println("TM_VERSION=${TM_VERSION}")

                                // Get data from TM
                                (SEEDS, RPC_SERVERS) = getSeedsAndRPCServers(remoteServerCometBFT)
                                (SEEDS, RPC_SERVERS) = appendMinimumSeedsAndRPCServers(env.NET_NAME, SEEDS, RPC_SERVERS)
                                Collections.shuffle(SEEDS as List)
                                Collections.shuffle(RPC_SERVERS as List)
                                SEEDS = SEEDS.take(2).join(",")
                                RPC_SERVERS = RPC_SERVERS.take(2).join(",")
                                println("SEEDS=${SEEDS}")
                                println("RPC_SERVERS=${RPC_SERVERS}")

                            } catch (e) {
                                if ( !isDataNodeHealthy(remoteServerDataNode) ) {
                                    // Remote server stopped being available.
                                    // This is quite often for Devnet, when deployments happen all the time
                                    extraMsg = extraMsg ?: "${env.NET_NAME} seems down. Snapshot test aborted."
                                    currentBuild.result = 'ABORTED'
                                    error("${env.NET_NAME} seems down")
                                } else {
                                    println("Remote server ${remoteServerDataNode} is still up.")
                                    // re-throw
                                    throw e
                                }
                            }
                        }
                    }
                    stage("Set configs") {
                        parallel(
                            failFast: false,
                            'tendermint': {
                                sh label: 'set Tendermint config',
                                    script: """#!/bin/bash -e
                                        ./dasel put bool -f tm_config/config/config.toml statesync.enable true
                                        ./dasel put string -f tm_config/config/config.toml statesync.trust_period "744h0m0s"
                                        ./dasel put string -f tm_config/config/config.toml statesync.trust_hash ${SNAPSHOT_HASH}
                                        ./dasel put int -f tm_config/config/config.toml statesync.trust_height ${SNAPSHOT_HEIGHT}
                                        ./dasel put string -f tm_config/config/config.toml statesync.rpc_servers ${RPC_SERVERS}
                                        ./dasel put string -f tm_config/config/config.toml statesync.discovery_time "30s"
                                        ./dasel put string -f tm_config/config/config.toml statesync.chunk_request_timeout "60s"
                                        ./dasel put string -f tm_config/config/config.toml p2p.seeds ${SEEDS}
                                        ./dasel put string -f tm_config/config/config.toml p2p.dial_timeout "10s"
                                        ./dasel put int -f tm_config/config/config.toml p2p.max_packet_msg_payload_size 16384
                                        ./dasel put string -f tm_config/config/config.toml p2p.external_address "${jenkinsAgentIP}:26656"
                                        ./dasel put bool -f tm_config/config/config.toml p2p.allow_duplicate_ip true
                                    """
                                if (env.NET_NAME == 'validators-testnet') {
                                    sh label: 'set Tendermint config (validators-testnet specific)',
                                        script: """#!/bin/bash -e
                                            ./dasel put string -f tm_config/config/config.toml statesync.rpc_servers "sn010.validators-testnet.vega.rocks:40107,sn011.validators-testnet.vega.rocks:40117"
                                        """
                                }
                                sh label: 'print tendermint config',
                                    script: '''#!/bin/bash -e
                                        cat tm_config/config/config.toml
                                    '''
                            },
                            'vega': {
                                sh label: 'set vega config',
                                    script: """#!/bin/bash -e
                                        ./dasel put bool -f vega_config/config/node/config.toml Broker.Socket.Enabled true
                                        ./dasel put string -f vega_config/config/node/config.toml Broker.Socket.DialTimeout "4h"
                                        ./dasel put bool -f vega_config/config/node/config.toml Metrics.Enabled true
                                        ./dasel put int -f vega_config/config/node/config.toml Metrics.Port 2112
                                        cat vega_config/config/node/config.toml
                                    """
                            },
                            'data-node': {
                                sh label: 'set data-node config',
                                    script: """#!/bin/bash -e
                                        ./dasel put bool -f vega_config/config/data-node/config.toml AutoInitialiseFrom${env.HISTORY_KEY} true
                                        ./dasel put bool -f vega_config/config/data-node/config.toml SQLStore.UseEmbedded false
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Host 127.0.0.1
                                        ./dasel put int -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Port 5432
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Username vega
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Password vega
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Database vega
                                        ./dasel put string -f vega_config/config/data-node/config.toml NetworkHistory.Initialise.TimeOut "4h"
                                        ./dasel put string -f vega_config/config/data-node/config.toml NetworkHistory.RetryTimeout "30s"
                                        ./dasel put int -f vega_config/config/data-node/config.toml NetworkHistory.Initialise.MinimumBlockCount 2001
                                        ./dasel put bool -f vega_config/config/data-node/config.toml Metrics.Enabled true
                                        ./dasel put int -f vega_config/config/data-node/config.toml Metrics.Port 2113
                                        sed -i 's|.*BootstrapPeers.*|    BootstrapPeers = ${NETWORK_HISTORY_PEERS}|g' vega_config/config/data-node/config.toml
                                        cat vega_config/config/data-node/config.toml
                                    """
                                    // ^ easier to use sed rather than dasel. number of spaces is hardcoded and NETWORK_HISTORY_PEERS var is in toml compatible format (minimized JSON)
                            },
                            'postgres': {
                                // jenkins needs to be added to postgres image and be added to postgres group so it's files loaded from actual workspace by volume binding are able to be loaded into database
                                // to do we overwrite entrypoint, provision user and then restore original entrypoint
                                // it can be found here in the layers history (combination of ENTRYPOINT and CMD) -> https://hub.docker.com/layers/timescale/timescaledb/latest-pg14/images/sha256-73ec414f1dd66eec68682da9c0c689574cecf5fbe1683f649c77233bb83c30f2?context=explore
                                UID = sh(
                                    script: 'id -u $(whoami)',
                                    returnStdout: true
                                ).trim()
                                writeFile(
                                    file: 'start-postgres.sh',
                                    text: """#!/bin/bash -ex
                                        whoami
                                        adduser jenkins -D -u ${UID} -G postgres
                                        chmod -R a+rwx /jenkins/workspace/
                                        bash docker-entrypoint.sh postgres
                                    """
                                )
                                sh 'cat start-postgres.sh; chmod +x start-postgres.sh'
                            }
                        )
                    }
                    stage('Run') {
                        parallel(
                            failFast: true,
                            'Postgres': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    sh label: 'run postgres',
                                        script: '''#!/bin/bash -e
                                            docker run \
                                                -e POSTGRES_PASSWORD=vega \
                                                -e POSTGRES_DB=vega \
                                                -e POSTGRES_USER=vega \
                                                -v /jenkins/workspace:/jenkins/workspace \
                                                -v $PWD/start-postgres.sh:/start-postgres.sh \
                                                -u root \
                                                --entrypoint '/start-postgres.sh' \
                                                -p 5432:5432 \
                                                    timescale/timescaledb:latest-pg14
                                        '''
                                }
                            },
                            'Data node': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    // wait for db
                                    sleep(time: '30', unit:'SECONDS')
                                    sh label: 'run data node',
                                        script: """#!/bin/bash -e
                                            ./vega datanode start --home=vega_config
                                        """
                                }
                            },
                            'Vega': {
                                boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                    sleep(time: '35', unit:'SECONDS')
                                    sh label: 'Start vega node',
                                        script: """#!/bin/bash -e
                                            ./vega start --home=vega_config \
                                                --tendermint-home=tm_config \
                                                --snapshot.log-level=debug \
                                                --snapshot.load-from-block-height=${SNAPSHOT_HEIGHT}
                                        """
                                }
                                archiveArtifacts(
                                    artifacts: 'tm_config/**/*',
                                    allowEmptyArchive: true
                                )
                                archiveArtifacts(
                                    artifacts: 'vega_config/**/*',
                                    allowEmptyArchive: true
                                )
                                if ( !nice && isDataNodeHealthy(remoteServerDataNode) ) {
                                    extraMsg = extraMsg ?: "Vega core stopped too early."
                                    error("Vega stopped too early, Remote Server is still alive.")
                                }
                            },
                            'Checks': {
                                nicelyStopAfter(Integer.toString(params.TIMEOUT.toInteger() - 1)) {
                                    int startAt = currentBuild.duration
                                    sleep(time: '60', unit:'SECONDS')
                                    // run at 20sec, 50sec, 1min20sec, 1min50sec, 2min20sec, ... since start
                                    int runEverySec = 10
                                    int runEveryMs = runEverySec * 1000
                                    int previousLocalHeight = -1
                                    // String currTime = currentBuild.durationString - ' and counting'
                                    String timeSinceStartSec = Math.round((currentBuild.duration - startAt)/1000)
                                    println("Checks are run every ${runEverySec} seconds")
                                    while (true) {
                                        // wait until next 20 or 50 sec past full minute since start
                                        int sleepForMs = runEveryMs - ((currentBuild.duration - startAt + 10 * 1000) % runEveryMs)
                                        sleep(time:sleepForMs, unit:'MILLISECONDS')

                                        if (!blockHeightIncreased) {

                                            String remoteServerStats = sh(
                                                    script: "curl --max-time 5 https://${remoteServerDataNode}/statistics || echo '{}'",
                                                    returnStdout: true,
                                                ).trim()
                                            println("https://${remoteServerDataNode}/statistics\n${remoteServerStats}")
                                            Object remoteStats = new groovy.json.JsonSlurperClassic().parseText(remoteServerStats)
                                            String localServerStats = sh(
                                                    script: "curl --max-time 5 http://127.0.0.1:3008/statistics || echo '{}'",
                                                    returnStdout: true,
                                                ).trim()
                                            println("http://127.0.0.1:3008/statistics\n${localServerStats}")
                                            Object localStats = new groovy.json.JsonSlurperClassic().parseText(localServerStats)

                                            if (networkVersion == null || networkVersion.length() < 1) {
                                                networkVersion = localStats?.statistics?.appVersion
                                            }

                                            if (!chainStatusConnected) {
                                                if (localStats?.statistics?.status == "CHAIN_STATUS_CONNECTED") {
                                                    chainStatusConnected = true
                                                    // currTime = currentBuild.durationString - ' and counting'
                                                    timeSinceStartSec = Math.round((currentBuild.duration - startAt)/1000)
                                                    println("====>>> Node has reached status CHAIN_STATUS_CONNECTED !! (${timeSinceStartSec} sec) <<<<====")
                                                }
                                            }

                                            // don't use else, to run next test in the same iteration
                                            if (chainStatusConnected) {
                                                int remoteHeight = remoteStats?.statistics?.blockHeight?.toInteger() ?: 0
                                                int localHeight = localStats?.statistics?.blockHeight?.toInteger() ?: 0
                                                if (localHeight > 0) {
                                                    if (previousLocalHeight < 0) {
                                                        previousLocalHeight = localHeight
                                                    } else if (localHeight > previousLocalHeight) {
                                                        blockHeightIncreased = true
                                                        // currTime = currentBuild.durationString - ' and counting'
                                                        timeSinceStartSec = Math.round((currentBuild.duration - startAt)/1000)
                                                        println("====>>> Detected that block has increased from ${previousLocalHeight} to ${localHeight} (${timeSinceStartSec} sec) <<<<====")
                                                    }
                                                }
                                            }
                                        }

                                        // don't use else, to run next test in the same iteration
                                        if (blockHeightIncreased) {
                                            if (!caughtUp) {
                                                if ( isLocalDataNodeHealthy(true) ) {
                                                    caughtUp = true
                                                    // catchupTime = currentBuild.durationString - ' and counting'
                                                    timeSinceStartSec = Math.round((currentBuild.duration - startAt)/1000)
                                                    catchupTime = "${timeSinceStartSec} sec"
                                                    println("====>>> Data Node has caught up with the vega network !! (${timeSinceStartSec} sec) <<<<====")
                                                    // still take samples every 10sec, to investigate potential issue on Devnet
                                                    // runEveryMs *= 2
                                                    // println("Increasing delay between checks to ${runEveryMs/1000} seconds")
                                                }
                                            } else {
                                                if ( !isLocalDataNodeHealthy(true) ) {
                                                    notHealthyAgainCount += 1
                                                    println("!!!!!!!!!!!!!! Data Node is not healthy again !!!!!!!!!!!!!")
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            'Info': {
                                echo "Jenkins Agent Public IP: ${jenkinsAgentIP}. Some useful links:"
                                echo "Core stats: http://${jenkinsAgentIP}:3003/statistics"
                                echo "GraphQL: http://${jenkinsAgentIP}:3008/graphql/"
                                echo "Epoch: http://${jenkinsAgentIP}:3008/api/v2/epoch"
                                echo "Data-Node stats: http://${jenkinsAgentIP}:3008/statistics"
                                echo "External Data-Node stats: https://${remoteServerDataNode}/statistics"
                                echo "CometBFT: ${remoteServerCometBFT}/net_info"
                                echo "Monitoring Dashboard: ${monitoringDashboardURL}"
                            }
                        )
                    }
                    stage("Verify checks") {
                        if (!chainStatusConnected) {
                            currentBuild.description += "no CHAIN_STATUS_CONNECTED"
                            extraMsg = extraMsg ?: "Not reached CHAIN_STATUS_CONNECTED."
                            error("Non-validator never reached CHAIN_STATUS_CONNECTED status.")
                        }
                        echo "Chain status connected: ${chainStatusConnected}"
                        if (!blockHeightIncreased) {
                            currentBuild.description += "block did not increase"
                            extraMsg = extraMsg ?: "block height didn't increase."
                            error("Non-validator block height did not incrase.")
                        }
                        echo "Block height increased: ${blockHeightIncreased}"
                        if (!caughtUp) {
                            currentBuild.description += "did not catch up"
                            extraMsg = extraMsg ?: "didn't catch up with network."
                            error("Non-validator did not catch up.")
                        }
                        if (notHealthyAgainCount > 0) {
                            currentBuild.description += "became unhealthy (${notHealthyAgainCount})"
                            extraMsg = extraMsg ?: "became unhealthy ${notHealthyAgainCount} times."
                            error("Non-validator became unhealthy ${notHealthyAgainCount} times.")
                        }
                        echo "Caught up: ${caughtUp}"
                        println("All checks passed.")
                    }
                }

                stage('Backup snapshots') {
                    if (params.BACKUP_SNAPSHOTS) {
                        gitClone(
                            url: 'git@github.com:vegaprotocol/snapshot-backups.git',
                            branch: 'main',
                            directory: 'snapshot-backups',
                            credentialsId: 'vega-ci-bot',
                            timeout: 3,
                        )

                        // Example output:
                        // { "snapshots":[
                        //   {
                        //     "height":774000,
                        //     "version":774,"size":88,
                        //     "hash":"449ac952c29c615fcf8ea9ee14ffe056fec034740b3a879c9c6818563f83fb75"
                        //   },
                        //   {"height":773000,...},
                        //   ...
                        // ]}
                        String snapshotsDetailsJSON = vegautils.shellOutput('''./vega tools snapshot \
                                --db-path ./vega_config/state/node/snapshots \
                                --output json''')

                        Map snapshotDetails = readJSON text: snapshotsDetailsJSON

                        List snapshotsHeight = snapshotDetails?.snapshots.collect{ it?.height as int }

                        sh 'mkdir -p snapshot-backups/' + env.NET_NAME + '/' + networkVersion + '/' + snapshotsHeight.min() + '-' + snapshotsHeight.max()

                        sh 'tar -czvf ' + snapshotsHeight.min() + '-' + snapshotsHeight.max() + '.tar.gz vega_config/state/node/snapshots'
                        sh '''cp ''' + snapshotsHeight.min() + '''-''' + snapshotsHeight.max() + '''.tar.gz \
                            snapshot-backups/''' + env.NET_NAME + '''/''' + networkVersion + '''/''' + snapshotsHeight.min() + '''-''' + snapshotsHeight.max()

                        makeCommit(
                            makeCheckout: true,
                            directory: 'snapshot-backups',
                            url: 'git@github.com:vegaprotocol/snapshot-backups.git',
                            branchName: 'snapshot-' + env.NET_NAME + '-' + snapshotsHeight.min() + '-' + snapshotsHeight.max(),
                            commitMessage: '[Automated] Snapshot backup for ' + env.NET_NAME + ', blocks ' + snapshotsHeight.min() + '-' + snapshotsHeight.max(),
                            commitAction: 'git add ' + env.NET_NAME + '/' + networkVersion + '/' + snapshotsHeight.min() + '-' + snapshotsHeight.max()
                        )
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
                    sendSlackMessage(env.NET_NAME, extraMsg, catchupTime)
                }
                stage('cleanup') {
                    script {
                        // cleanup grafana
                        grafanaAgent.stop()
                        grafanaAgent.cleanup()
                    }
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

boolean isDataNodeHealthy(String serverURL, boolean tls = true, boolean debug = false) {
    try {
        def conn = new URL("http${tls ? 's' : ''}://${serverURL}/statistics").openConnection()
        conn.setConnectTimeout(1000)
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            if (debug) {
                println("Data Node healthcheck failed: response code ${conn.getResponseCode()} not 200 for ${serverURL}")
            }
            return false
        }
        int datanode_height = (conn.getHeaderField("x-block-height") ?: "-1") as int
        if (datanode_height < 0) {
            if (debug) {
                println("Data Node healthcheck failed: missing x-block-height response header for ${serverURL}")
            }
            return false
        }
        def stats = new groovy.json.JsonSlurperClassic().parseText(conn.getInputStream().getText())
        int core_height = stats.statistics.blockHeight as int
        if ((core_height - datanode_height).abs() > 10) {
            if (debug) {
                println("Data Node healthcheck failed: data node (${datanode_height}) is more than 10 blocks behind core (${core_height}) for ${serverURL}")
            }
            return false
        }
        Date vega_time = Date.parse("yyyy-MM-dd'T'HH:mm:ss", stats.statistics.vegaTime.split("\\.")[0])
        Date current_time = Date.parse("yyyy-MM-dd'T'HH:mm:ss", stats.statistics.currentTime.split("\\.")[0])
        if (TimeCategory.plus(vega_time, TimeCategory.getSeconds(10)) < current_time) {
            if (debug) {
                println("Data Node healthcheck failed: data node (${vega_time}) is more than 10 seconds behind now (${current_time}) for ${serverURL}")
            }
            return false
        }
        return true
    } catch (IOException e) {
        if (debug) {
            println("Data Node healthcheck failed: exception ${e} for ${serverURL}")
        }
        return false
    }
}

boolean isLocalDataNodeHealthy(boolean debug = false) {
    try {
        String localServerStatsResponse = sh(
                script: "curl --max-time 5 -i http://127.0.0.1:3008/statistics || echo '{}'",
                returnStdout: true,
                encoding: 'UTF-8',
            ).trim()
        localServerStatsResponse = localServerStatsResponse.replaceAll("\r", "")
        def respParts = localServerStatsResponse.split("\n\n")
        if (respParts.size() != 2) {
            if (debug) {
                println("Data Node healthcheck failed: malformed response (${respParts.size()}) for local data-node:\n${localServerStatsResponse}")
            }
            return false
        }
        String localServerStatsBody = respParts[1]
        respParts = respParts[0].split("\n", 2)
        if (respParts.size() != 2) {
            if (debug) {
                println("Data Node healthcheck failed: missing response code (${respParts.size()}) for local data-node:\n${localServerStatsResponse}")
            }
            return false
        }
        String localServerStatsCode = respParts[0]
        String localServerStatsHeaders = respParts[1]
        if (!localServerStatsCode.contains("200")) {
            if (debug) {
                println("Data Node healthcheck failed: response code is not 200: ${localServerStatsCode} for local data-node")
            }
            return false
        }
        def headerMatcher = (localServerStatsHeaders =~ /(?i)X-Block-Height: (.*)\n/)
        if (!headerMatcher.find()) {
            if (debug) {
                println("Data Node healthcheck failed: missing x-block-height response header for local data-node")
            }
            return false
        }
        int datanode_height = headerMatcher[0][1] as int
        def stats = new groovy.json.JsonSlurperClassic().parseText(localServerStatsBody)
        int core_height = stats.statistics.blockHeight as int
        if ((core_height - datanode_height).abs() > 10) {
            if (debug) {
                println("Data Node healthcheck failed: data node (${datanode_height}) is more than 10 blocks behind core (${core_height}) for local data-node")
            }
            return false
        }
        Date vega_time = Date.parse("yyyy-MM-dd'T'HH:mm:ss", stats.statistics.vegaTime.split("\\.")[0])
        Date current_time = Date.parse("yyyy-MM-dd'T'HH:mm:ss", stats.statistics.currentTime.split("\\.")[0])
        if (TimeCategory.plus(vega_time, TimeCategory.getSeconds(10)) < current_time) {
            if (debug) {
                println("Data Node healthcheck failed: core (${vega_time}) is more than 10 seconds behind now (${current_time}) for local data-node")
            }
            return false
        }
        return true
    } catch (IOException e) {
        if (debug) {
            println("Data Node healthcheck failed: exception ${e} for local data-node")
        }
        return false
    }

}

void sendSlackMessage(String vegaNetwork, String extraMsg, String catchupTime) {
    String slackChannel = '#snapshot-notify'
    String slackFailedChannel = '#snapshot-notify-failed'
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

    if (currentResult != 'SUCCESS') {
        slackSend(
            channel: slackFailedChannel,
            color: color,
            message: msg,
        )
    }
}

boolean checkServerListening(String serverHost, int serverPort) {
  timeoutMs = 1000
  Socket s = null
  try {
    s = new Socket()
    s.connect(new InetSocketAddress(serverHost, serverPort), timeoutMs);
    return true
  } catch (Exception e) {
    return false
  } finally {
    if(s != null) {
    try {s.close();}
    catch(Exception e){}
    }
  }
}

// When validators or any peer of the network parameters
// does not expose required endpoints, let's add our own servers
// to provide minimal number of peers required to start the networks
Tuple2<List, List> appendMinimumSeedsAndRPCServers(String networkName, List<String> seeds, List<String> rpcServers) {
    Map<String, List<String>> internalNodes = [
        "mainnet": ["api0.vega.community", "api1.vega.community", "api2.vega.community", "api3.vega.community"]
    ]

    if (networkName != "mainnet") {
        return new Tuple2(seeds, rpcServers)
    }

    // Tendermint requires at least 2 seeds and rpc ports otherwise it fails
    if (seeds.size() < 2) {
        newSeeds = internalNodes[networkName].stream().limit(2);
        seeds = seeds + newSeeds.collect {
            it + ':26656'
        }
    }
    if (rpcServers.size() < 2) {
        newRpcServers = internalNodes[networkName].stream().limit(2);
        rpcServers = rpcServers + newRpcServers.collect {
            it + ':26657'
        }
    }
    
    return new Tuple2(seeds.unique { a, b -> a <=> b }, rpcServers)
}

def getSeedsAndRPCServers(String cometURL) {
  print('Commet URL: ' + cometURL + '/net_info')
  def net_info_req = new URL("${cometURL}/net_info").openConnection()
  def net_info = new groovy.json.JsonSlurperClassic().parseText(net_info_req.getInputStream().getText())

  RPC_SERVERS = []
  SEEDS = []
  for(peer in net_info.result.peers) {
    // Get domain/IP address and port
    def addr = peer.node_info.listen_addr.minus('tcp://').split(":")
    print("Checking RPC peer: " + addr)
    if(addr.size()<2) {
      print("   > RPC address rejected: size < 2")
      continue
    }
    def port = addr[1] as int
    addr = addr[0]
    if(["0.0.0.0", "127.0.0.1"].contains(addr)) {
      print("   > RPC address rejected: localhost")
      continue
    }
    if(addr.startsWith("be")) {  // remove Block Explorers - as not stable
      print("   > RPC address rejected: block explorer")
      continue
    }
    if( ! checkServerListening(addr, port)) {
      print("   > RPC address rejected: not listening")
      continue
    }
    // Get RPC port
    def rpc_port = peer.node_info.other.rpc_address.minus('tcp://').split(":")
    print("Checking RPC port: " + rpc_port)
    if(rpc_port.size()<2) {
      print("   > RPC port rejected: size < 2")
      continue
    }
    rpc_port = rpc_port[1] as int
    if( ! checkServerListening(addr, rpc_port)) {
      print("   > RPC port rejected: not listening")
      continue
    }

    print("   > Done")
    // Get Peer ID
    def comet_peer_id = peer.node_info.id
    // Append result lists
    SEEDS.add("${comet_peer_id}@${addr}:${port}")
    RPC_SERVERS.add("${addr}:${rpc_port}")
  }
  new Tuple2(SEEDS, RPC_SERVERS)
}
