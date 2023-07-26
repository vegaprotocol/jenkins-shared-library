/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
void call(Map additionalConfig=[:], parametersOverride=[:]) {
  Map defaultConfig = [
    hooks: [:],
    fastFail: false,
    protocolUpgradeReleaseRepository: 'vegaprotocol/vega-dev-releases',
    extraEnvVars: [:],
    slackTitle: 'System Tests Capsule',
    slackChannel: '#qa-notify',
  ]

  // Helper variable to disable some steps when system-test variable
  // failed when the fastFail config is set to false
  boolean systmeTestFailed = false 

  Map config = defaultConfig + additionalConfig
  params = params + parametersOverride

  // Set default values for the parameters in case some job does not have this param defined
  // params.TIMEOUT = params.TIMEOUT ?: 60
  // params.ORIGIN_REPO = params.ORIGIN_REPO ?: 'vegaprotocol/vega'
  // params.VEGA_BRANCH = params.VEGA_BRANCH ?: 'develop'
  // params.SYSTEM_TESTS_BRANCH = params.SYSTEM_TESTS_BRANCH ?: 'develop'
  // params.VEGACAPSULE_BRANCH = params.VEGACAPSULE_BRANCH ?: 'main'
  // params.VEGATOOLS_BRANCH = params.VEGATOOLS_BRANCH ?: 'develop'
  // params.DEVOPSSCRIPTS_BRANCH = params.DEVOPSSCRIPTS_BRANCH ?: 'main'
  // params.DEVOPSTOOLS_BRANCH = params.DEVOPSTOOLS_BRANCH ?: 'main'
  // params.BUILD_PROTOCOL_UPGRADE_VERSION = params.BUILD_PROTOCOL_UPGRADE_VERSION ?: false
  // params.CAPSULE_CONFIG = params.CAPSULE_CONFIG ?: 'capsule_config.hcl'
  // params.ARCHIVE_VEGA_BINARY = params.ARCHIVE_VEGA_BINARY ?: false
  // params.SKIP_RUN_TESTS = params.SKIP_RUN_TESTS ?: false
  // params.SKIP_MULTISIGN_SETUP = params.SKIP_MULTISIGN_SETUP ?: false
  // params.SYSTEM_TESTS_TEST_FUNCTION = params.SYSTEM_TESTS_TEST_FUNCTION ?: ''
  // params.SYSTEM_TESTS_TEST_MARK = params.SYSTEM_TESTS_TEST_MARK ?: 'smoke'
  // params.SYSTEM_TESTS_TEST_DIRECTORY = params.SYSTEM_TESTS_TEST_DIRECTORY ?: ''
  // params.SYSTEM_TESTS_DEBUG = params.SYSTEM_TESTS_DEBUG ?: false
  // params.RUN_PROTOCOL_UPGRADE_PROPOSAL = params.RUN_PROTOCOL_UPGRADE_PROPOSAL ?: false
  // params.TEST_EXTRA_PYTEST_ARGS = params.TEST_EXTRA_PYTEST_ARGS ?: ''

  String agentLabel = params.NODE_LABEL ?: 'g-8vcpu-32gb'

  Map pipelineHooks = [
      postStartNomad: [:],
      postNetworkGenerate: [:],
      postNetworkStart: [:],
      runTests: [:],
      postRunTests: [:],
      preNetworkStop: [:],
      postPipeline: [:],
  ] + config.hooks

  String testNetworkDir = ''

  String protocolUpgradeVersion = 'v99.9.9-system-tests-' + currentBuild.number

  pipeline {
    agent {
      label agentLabel
    }

    options {
      ansiColor('xterm')
      timestamps()
      timeout(time: params.TIMEOUT, unit: 'MINUTES')
    }
    stages {
      stage('prepare') {
        steps {
          cleanWs()
          script {
            dir(pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
              testNetworkDir = pwd()
              networkPath = vegautils.escapePath(env.WORKSPACE + '/' + pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir)

              publicIP = agent.getPublicIP()
              print("The box public IP is: " + publicIP)
              print("You may want to visit the nomad web interface: http://" + publicIP + ":4646")
              print("The nomad interface is available only when the tests are running")

              print("Parameters")
              print("==========")
              print("${params}")
            }
          }
        }
      }

      stage('get source codes') {
        steps {
          script {
            def repositories = [
              [ name: params.ORIGIN_REPO, branch: params.VEGA_BRANCH, directory: 'vega' ],
              [ name: 'vegaprotocol/system-tests', branch: params.SYSTEM_TESTS_BRANCH ],
              [ name: 'vegaprotocol/vegacapsule', branch: params.VEGACAPSULE_BRANCH ],
              [ name: 'vegaprotocol/vegatools', branch: params.VEGATOOLS_BRANCH ],
              // [ name: 'vegaprotocol/devops-infra', branch: params.DEVOPS_INFRA_BRANCH ?: ], // TODO: Remove me
              [ name: 'vegaprotocol/devopsscripts', branch: params.DEVOPSSCRIPTS_BRANCH ],
              [ name: 'vegaprotocol/devopstools', branch: params.DEVOPSTOOLS_BRANCH ],
            ]
            def reposSteps = repositories.collectEntries{value -> [
                value.name,
                {
                  gitClone([
                    url: 'git@github.com:' + value.name + '.git',
                    branch: value.branch,
                    directory: value.directory ?: value.name.split('/')[1],
                    credentialsId: 'vega-ci-bot',
                    timeout: 2,
                  ])
                }
            ]}
            parallel reposSteps
          }
        }
      }

      stage('check') {
        environment {
          TESTS_DIR = "${testNetworkDir}"
        }
        steps {
          dir('system-tests/scripts') {
            sh 'make check'
          }
        }
      }

      stage('prepare environment') {
        parallel {
          stage('build devopsscripts') {
            options {
              timeout(time: 5, unit: 'MINUTES')
              retry(3)
            }
            steps {
              script {
                vegautils.buildGoBinary('devopsscripts',  testNetworkDir + '/devopsscripts', './')
              }
            }
          }
          stage('build devopstools') {
            options {
              timeout(time: 5, unit: 'MINUTES')
              retry(3)
            }
            steps {
              script {
                vegautils.buildGoBinary('devopstools',  testNetworkDir + '/devopstools', './')
              }
            }
          }
          stage('make vegacapsule'){
            options {
              timeout(time: 15, unit: 'MINUTES') // TODO: revert timeout to 10 min when build optimized
              retry(3)
            }
            environment {
              TESTS_DIR = "${testNetworkDir}"
            }
            steps {
              dir('system-tests/scripts') {
                sh 'make vegacapsule-prepare'
                sh 'make build-vegacapsule'
                sh 'make vegacapsule-cleanup'
              }
            }
          }
          stage('make visor'){
            options {
              timeout(time: 10, unit: 'MINUTES')
              retry(3)
            }
            environment {
              TESTS_DIR = "${testNetworkDir}"
            }
            steps {
              dir('system-tests/scripts') {
                sh 'make build-visor'
              }
            }
          }
          stage('make toxiproxy'){
            options {
              timeout(time: 10, unit: 'MINUTES')
              retry(3)
            }
            environment {
              TESTS_DIR = "${testNetworkDir}"
            }
            steps {
              dir('system-tests/scripts') {
                sh 'make build-toxiproxy'
              }
            }
          }
          stage('make vega tools'){
            options {
              timeout(time: 10, unit: 'MINUTES')
              retry(3)
            }
            environment {
              TESTS_DIR = "${testNetworkDir}"
            }
            steps {
              dir('system-tests/scripts') {
                sh 'make build-vega-tools'
              }
            }
          }
          stage('make test proto'){
            options {
              timeout(time: 10, unit: 'MINUTES')
              retry(3)
            }
            environment {
              TESTS_DIR = "${testNetworkDir}"
            }
            steps {
              dir('system-tests/scripts') {
                sh 'make build-test-proto'
              }
            }
          }
          stage('make core'){
            options {
              timeout(time: 10, unit: 'MINUTES')
              retry(3)
            }
            environment {
              TESTS_DIR = "${testNetworkDir}"
            }
            steps {
              dir('system-tests/scripts') {
                sh 'make build-vega-core'
                sh 'make build-vega-core-upgrade-version'
              }
            }
          }

          stage('prepare system tests dependencies') {
            options {
              timeout(time: 20, unit: 'MINUTES')
              retry(3)
            }
            steps {
              dir('system-tests') {
                // Use automatic pyenv resolution for installation & resolution
                sh label: 'Install python', script: '''
                  pyenv install --skip-existing
                '''

                sh label: 'Print versions', script: '''
                  python --version
                  poetry --version
                '''

                dir('scripts') {
                  sh label: 'Install poetry dependencies', script: '''
                    make poetry-install
                  '''
                }
              }
            }
          }
        }
      }

      stage('finalize preparation') {
        environment {
          TESTS_DIR = "${testNetworkDir}"
        }
        steps {
          dir('system-tests/scripts') {
            sh 'make prepare-tests'
          }
        }
      }

      stage('prepare upgrade binary') {
        when {
          expression {
            params.VEGA_BRANCH_UPGRADE != null && params.VEGA_BRANCH_UPGRADE != ''
          }
        }
        steps {
          script {
            // when a different branch specified for upgrade version, clone vega repository again, switch to the
            // `VEGA_BRANCH_UPGRADE` commit, and build a binary for a protocol upgrade
            gitClone([
              url: 'git@github.com:vegaprotocol/vega.git',
              branch: params.VEGA_BRANCH_UPGRADE,
              directory: 'vega-upgrade',
              credentialsId: 'vega-ci-bot',
              timeout: 2,
            ])

            dir('vega-upgrade') {
              sh '''sed -i 's/^\\s*cliVersion\\s*=\\s*".*"$/cliVersion="v99.99.0+dev"/' version/version.go'''
            }
            sh 'rm -f ' + testNetworkDir + '/vega-v99.99.0+dev || echo "OK"'
            vegautils.buildGoBinary('vega-upgrade',  testNetworkDir + '/vega-v99.99.0+dev', './cmd/vega')

            sh testNetworkDir + '/vega-v99.99.0+dev version'
          }
        }
      }

      stage('start nomad') {
        steps {
          script {
            dir ('system-tests/scripts') {
              String makeAbsBinaryPath = vegautils.shellOutput('which make')
              String cwd = vegautils.shellOutput('pwd')

              sh '''daemonize \
                -o ''' + testNetworkDir + '''/nomad.log \
                -e ''' + testNetworkDir + '''/nomad.log \
                -c ''' + cwd + ''' \
                -p ''' + testNetworkDir + '''/vegacapsule_nomad.pid \
                  ''' + makeAbsBinaryPath + ''' vegacapsule-start-nomad-only '''
            }
          }
        }
      }

      stage('post start nomad steps') {
        when {
          expression {
            pipelineHooks.containsKey('postStartNomad') && pipelineHooks.postStartNomad.size() > 0
          }
        }
        environment {
          PATH = "${networkPath}:${env.PATH}"
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_REPOSITORY = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? config.protocolUpgradeReleaseRepository : ''}"
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_VERSION = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? params.BUILD_PROTOCOL_UPGRADE_VERSION : ''}"
        }

        steps {
          script {
            parallel pipelineHooks.postStartNomad
          }
        }
      }

      stage('generate network config') {
        environment {
          PATH = "${networkPath}:${env.PATH}"
          TESTS_DIR = "${testNetworkDir}"
          VEGACAPSULE_CONFIG_FILENAME = "${env.WORKSPACE}/system-tests/vegacapsule/${params.CAPSULE_CONFIG}"
        }

        steps {
          script {
            timeout(time: 3, unit: 'MINUTES') {
              dir ('system-tests/scripts') {
                sh 'make vegacapsule-generate-network'
              }
            }
          }
        }
      }

      stage('post network generate steps') {
        when {
          expression {
            pipelineHooks.containsKey('postNetworkGenerate') && pipelineHooks.postNetworkGenerate.size() > 0
          }
        }
        environment {
          PATH = "${networkPath}:${env.PATH}"
        }

        steps {
          script {
            parallel pipelineHooks.postNetworkGenerate
          }
        }
      }

      stage('Archive vega binary') {
        steps {
          script {
            if (params.ARCHIVE_VEGA_BINARY) {
              dir(testNetworkDir) {
                // binary is copied in the Makefile into the testNetworkDir, ref: https://github.com/vegaprotocol/system-tests/blob/develop/scripts/Makefile#L198
                archiveArtifacts(
                  artifacts: 'vega',
                )
              }
            }
          }
        }
      }

      stage('start network') {
        environment {
          PATH = "${networkPath}:${env.PATH}"
          TESTS_DIR = "${testNetworkDir}"
          VEGACAPSULE_CONFIG_FILENAME = "${env.WORKSPACE}/system-tests/vegacapsule/${params.CAPSULE_CONFIG}"
        }

        steps {
          script {
            dir('system-tests/scripts') {
              sh 'make vegacapsule-start-network-only'
            }
          }
        }
      }

      stage('setup multisig contract') {
        when {
          not {
            expression {
              params.SKIP_MULTISIGN_SETUP
            }
          }
        }

        options {
          timeout(time: 2, unit: 'MINUTES')
        }
        steps {
          dir('system-tests/scripts') {
            sh 'make vegacapsule-setup-multisig'
          }
        }
      }

      stage('post start network steps') {
        when {
          expression {
            pipelineHooks.containsKey('postNetworkStart') && pipelineHooks.postNetworkStart.size() > 0
          }
        }
        environment {
          PATH = "${env.PATH}:${networkPath}"
        }

        options {
          timeout(time: 10, unit: 'MINUTES')
        }

        steps {
          script {
            parallel pipelineHooks.postNetworkStart
          }
        }
      }

      stage('run tests') {
        when {
          not {
            expression {
              params.SKIP_RUN_TESTS
            }
          }
        }
        environment {
          TESTS_DIR = "${testNetworkDir}"
          NETWORK_HOME_PATH = "${testNetworkDir}/testnet"
          TEST_FUNCTION= "${params.SYSTEM_TESTS_TEST_FUNCTION}"
          TEST_MARK= "${params.SYSTEM_TESTS_TEST_MARK}"
          TEST_DIRECTORY= "${params.SYSTEM_TESTS_TEST_DIRECTORY}"
          USE_VEGACAPSULE= 'true'
          SYSTEM_TESTS_DEBUG = "${params.SYSTEM_TESTS_DEBUG}"
          SYSTEM_TESTS_LOG_OUTPUT="${testNetworkDir}/log-output"
          PATH = "${networkPath}:${env.PATH}"
          VEGACAPSULE_CONFIG_FILENAME = "${env.WORKSPACE}/system-tests/vegacapsule/${params.CAPSULE_CONFIG}"
          SYSTEM_TESTS_NETWORK_PARAM_OVERRIDES = "${params.SYSTEM_TESTS_NETWORK_PARAM_OVERRIDES ? params.SYSTEM_TESTS_NETWORK_PARAM_OVERRIDES : ''}"

        }

        steps {
          withCredentials([
            usernamePassword(credentialsId:  vegautils.getVegaCiBotCredentials(), passwordVariable: 'GITHUB_API_TOKEN', usernameVariable:'GITHUB_API_USER')
          ]) {
            script {
              Map runStages = [
                'run system-tests': {
                  dir('system-tests/scripts') {
                    try {
                      sh 'make test'
                    } catch(err) {
                      systmeTestFailed = true
                      print("FAILURE AFTER make test")
                      // We have some scenarios, where We do not want to stop pipeline here(e.g. LNL), but we still want to report failure
                      currentBuild.result = 'FAILURE'
                      if (!config.fastFail) {
                        print(err)
                      } else {
                        throw err
                      }
                    }
                  }
                }
              ]
              if (pipelineHooks.containsKey('runTests') && pipelineHooks.runTests.size() > 0) {
                runStages = runStages + pipelineHooks.runTests
              }

              withEnv(config?.extraEnvVars.collect{entry -> entry.key + '=' + entry.value}) {
                sh 'printenv'
                parallel runStages
              }
            }
          }
        }
      }

      stage('post run tests steps') {
        when {
          expression {
            pipelineHooks.containsKey('postRunTests') && pipelineHooks.postRunTests.size() > 0
          }
        }
        environment {
          PATH = "${networkPath}:${env.PATH}"
        }

        steps {
          script {
            withEnv(config?.extraEnvVars.collect{entry -> entry.key + '=' + entry.value}) {
              parallel pipelineHooks.postRunTests
            }
          }
        }
      }

      stage('protocol upgrade proposal step') {
        when {
          expression {
            params.RUN_PROTOCOL_UPGRADE_PROPOSAL
          }
        }
        environment {
          PATH = "${networkPath}:${env.PATH}"
        }
        options {
          timeout(time: 10, unit: 'MINUTES')
        }

        steps {
          script {
            // We cannot just start the data node as it is because we do not know what binary is
            // currently running in the network. We may expect protocol upgrades/binaries shifts, etc.
            // We have a simple helper that checks each node, compares height, and selects
            // binary from the node at the highest block.
            //
            // We copy the binary which is running on the network to `<testNetworkDir>/vega-latest`
            sh '''devopstools vegacapsule \
              get-live-binary \
              --network-home-path ''' + testNetworkDir + '''/testnet \
              --copy-to ''' + testNetworkDir + '''/vega-latest \
              --debug
            '''


            int upgradeProposalOffset = 100
            def getLastBlock = { String restURL, boolean silent ->
              return vegautils.shellOutput('''curl ''' + restURL + '''/statistics | jq -r '.statistics.blockHeight' ''', silent).toInteger()
            }

            sh '''devopstools vegacapsule \
              start-datanode-from-network-history \
              --base-on-group "no_visor_data_node" \
              --network-home-path ''' + testNetworkDir + '''/testnet \
              --out ''' + testNetworkDir + '''/new-node-info.json \
              --wait-for-replay
            '''

            String restURL = vegautils.shellOutput('''jq -r '.GatewayURL' ''' + testNetworkDir + '''/new-node-info.json''')
            String grpcURL = vegautils.shellOutput('''jq -r '.GRPCURL' ''' + testNetworkDir + '''/new-node-info.json''')
            String vegaHomePath = vegautils.shellOutput('''jq -r '.CoreConfigFilePath' ''' + testNetworkDir + '''/new-node-info.json''')

            int initNetworkHeight = getLastBlock(restURL, false)
            int proposalBlock = initNetworkHeight + upgradeProposalOffset
            print('Current network heigh is ' + initNetworkHeight)
            print('Proposing protocol upgrade on block ' + proposalBlock)

            // The release tag needs to be valid vega tag.
            // Given version must be higher than current network version.
            // It does not need to be existing release because we are not
            // doing real upgrade. We just need vega network to stop
            // producing blocks.
            sh '''vegacapsule nodes protocol-upgrade \
                --propose \
                --home-path ''' + testNetworkDir + '''/testnet \
                --template-path system-tests/vegacapsule/net_configs/visor_run.tmpl \
                --height ''' + proposalBlock + ''' \
                --release-tag v99.990.0
            '''

            print('Waiting on block ' + proposalBlock)
            waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
                int currentNetworkHeight = getLastBlock(restURL, true)
                print('... still waiting, network heigh is ' + currentNetworkHeight)
                return (currentNetworkHeight >= proposalBlock)
            }
            initNetworkHeight = getLastBlock(restURL, false)
            print('Current network heigh is ' + initNetworkHeight)


            print('Run snapshot checks')
            sleep '5'
            sh '''
              mkdir -p ./snapshot-tmp;
              rsync -av ''' + testNetworkDir + '''/testnet/vega/node1/state/node/snapshots/ ./snapshot-tmp;
              ls -als ./snapshot-tmp;
            '''

            try {
              sh '''vegatools difftool \
                -s "./snapshot-tmp" \
                -d "''' + grpcURL + '''"'''
            } catch (err) {
                echo err.getMessage()
            }
          }
        }
      }

      stage("SOAK Test") {
        when {
          expression {
            params.RUN_SOAK_TEST
          }
        }
        environment {
          PATH = "${networkPath}:${env.PATH}"
        }
        options {
          timeout(time: 20, unit: 'MINUTES')
        }

        steps {
          script {
            // Find an index for the data-node
            String dataNodeIndex = vegautils.shellOutput('''vegacapsule nodes ls \
                --home-path ''' + testNetworkDir + '''/testnet \
              | jq -r '.[] | select(.Mode | contains("full")) | .Index';
            ''')
            String nodeName = 'node' + dataNodeIndex
            String tmHome = testNetworkDir + '/testnet/tendermint/' + nodeName
            String vegaHome = testNetworkDir + '/testnet/vega/' + nodeName
            String vegaBinary = 'vega' // comes from PATH

            sh vegaBinary + ' version'

            String systemTestsPath = ""
            dir('system-tests') {
              systemTestsPath = vegautils.shellOutput('pwd')
            }

            // Ensure network is stopped
            dir(testNetworkDir) {
              sh './vegacapsule network stop --home-path ' + testNetworkDir + '/testnet 2>/dev/null'
            }
            
            // Run in separated folder because script produces a lot of logs and We want 
            // to avoid having them in the system-tests dir.
            dir("soak-test") {
                String cwd = vegautils.shellOutput('pwd')
                sh '''
                    cd ''' + systemTestsPath + ''';
                    . $(poetry env info --path)/bin/activate
                    cd ''' + cwd + ''';

                    python "''' + systemTestsPath + '''/tests/soak-test/run.py" \
                      --tm-home="''' + tmHome + '''" \
                      --vega-home="''' + vegaHome + '''" \
                      --vega-binary="''' + vegaBinary + '''" \
                      --replay

                    python "''' + systemTestsPath + '''/tests/soak-test/run.py" \
                      --tm-home="''' + tmHome + '''" \
                      --vega-home="''' + vegaHome + '''" \
                      --vega-binary="''' + vegaBinary + '''"
                '''
            }
          }
        }
      }
    }

    post {
      always {
        catchError {
          script {
            if (pipelineHooks.containsKey('preNetworkStop') && pipelineHooks.preNetworkStop.size() > 0) {
              parallel pipelineHooks.preNetworkStop
            }
          }
        }
        catchError {
          dir(testNetworkDir) {
            sh './vegacapsule network stop --home-path ' + testNetworkDir + '/testnet 2>/dev/null'
          }
        }

        catchError {
          dir('docker-inspect') {
            sh label: 'create folder to dump container informations',
              script: 'mkdir docker-containers'
            sh label: 'dump docker containers info',
              script: '''for docker_id in $(docker ps --all --format "{{- .ID -}}"); do
                docker inspect "$docker_id" > "$docker_id.log" || echo "Container $docker_id not found";
              done;'''

            archiveArtifacts(
              artifacts: 'docker-containers/*.log',
              allowEmptyArchive: true
            )
          }
        }

        catchError {
          script {
            if (params.RUN_SOAK_TEST) {
              archiveArtifacts(
                  artifacts: "soak-test/**/**/node-*.log",
                  allowEmptyArchive: true,
              )
              archiveArtifacts(
                  artifacts: "soak-test/**/**/err-node-*.log",
                  allowEmptyArchive: true,
              )
            }
          }
        }

        dir(testNetworkDir) {
          archiveArtifacts(
            artifacts: 'testnet/**/*',
            excludes: [
              'testnet/**/*.sock',
              // 'testnet/data/**/state/data-node/**/*', # https://github.com/vegaprotocol/jenkins-shared-library/issues/549
              // do not archive vega binaries
              'testnet/visor/**/vega',
              'testnet/visor/**/data-node',
            ].join(','),
            allowEmptyArchive: true
          )
          archiveArtifacts(
            artifacts: 'nomad.log',
            allowEmptyArchive: true
          )
          script {
            if (fileExists('log-output')) {
              archiveArtifacts(
                artifacts: 'log-output/**/*',
                allowEmptyArchive: true
              )
            }
          }
        }

        dir('system-tests') {
          archiveArtifacts(
            artifacts: 'build/test-reports/**/*',
            allowEmptyArchive: true
          )
          archiveArtifacts(
            artifacts: 'test_logs/**/*',
            allowEmptyArchive: true
          )
          archiveArtifacts(
            artifacts: 'checkpoints/**/*',
            allowEmptyArchive: true
          )
          script {
            if (params.TEST_EXTRA_PYTEST_ARGS.contains("--profile")) {
              archiveArtifacts(
                  artifacts: 'prof/**/*',
                  allowEmptyArchive: true
              )
            }
          }

          catchError {
            junit(
              checksName: 'System Tests',
              testResults: 'build/test-reports/system-test-results.xml',
              skipMarkingBuildUnstable: false,
              skipPublishingChecks: false,
            )
          }
        }

        archiveArtifacts(
          artifacts: pipelineDefaults.art.systemTestCapsuleJunit,
          allowEmptyArchive: true
        )

        catchError {
          script {
            if (pipelineHooks.containsKey('postPipeline') && pipelineHooks.postPipeline.size() > 0) {
              parallel pipelineHooks.postPipeline
            }
          }
        }
        script {
          slack.slackSendCIStatus(
            name: config.slackTitle,
            channel: config.slackChannel,
            branch: 'st:' + params.SYSTEM_TESTS_BRANCH + ' | vega:' + params.VEGA_BRANCH
          )
        }
        cleanWs()
      }
    }
  }
}
