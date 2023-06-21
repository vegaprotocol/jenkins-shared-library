/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
void call(Map additionalConfig=[:], parametersOverride=[:]) {
  Map defaultConfig = [
    hooks: [:],
    agentLabel: 'system-tests-capsule',
    fastFail: true,
    protocolUpgradeReleaseRepository: 'vegaprotocol/vega-dev-releases',
    extraEnvVars: [:],
    slackTitle: 'System Tests Capsule',
    slackChannel: '#qa-notify',
  ]

  Map config = defaultConfig + additionalConfig
  params = params + parametersOverride

  Map pipelineHooks = [
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
      label config.agentLabel
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
              [ name: 'vegaprotocol/devops-infra', branch: params.DEVOPS_INFRA_BRANCH ],
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
                  pyenv install 3.11.4 --skip-existing
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
      // stage('build upgrade binaries') {
      //   steps {
      //     script {
      //       dir('vega') {
      //           sh label: 'Build upgrade version of vega binary for tests', script: """#!/bin/bash -e
      //           sed -i 's/"v0.*"/"v99.99.0+dev"/g' version/version.go
      //           """
      //       }
      //       def binaries = [
      //         [ repository: 'vega', name: 'vega-v99.99.0+dev', packages: './cmd/vega' ],
      //       ]
      //       parallel binaries.collectEntries{value -> [
      //         value.name,
      //         {
      //           vegautils.buildGoBinary(value.repository,  testNetworkDir + '/' + value.name, value.packages)
      //         }
      //       ]}
      //     }
      //   }
      // }
      stage('build upgrade binaries') {
        when {
          expression {
            params.BUILD_PROTOCOL_UPGRADE_VERSION
          }
        }

        options {
          timeout(time: 10, unit: 'MINUTES')
          retry(2)
        }

        steps {
          script {
            sh 'mkdir -p vega/build'
            sh '''sed -i 's/^\\s*cliVersion\\s*=\\s*".*"$/cliVersion="''' + protocolUpgradeVersion + '''"/' vega/version/version.go'''
            vegautils.buildGoBinary('vega', 'build', './...')

            dir('vega/build') {
              sh './vega version'
              sh './data-node version'
              sh 'zip data-node-linux-amd64.zip data-node'
              sh 'zip vega-linux-amd64.zip vega'

              withGHCLI('credentialsId': vegautils.getVegaCiBotCredentials()) {
                sh '''gh release create \
                    --repo ''' + config.protocolUpgradeReleaseRepository + ''' \
                    ''' + protocolUpgradeVersion + ''' \
                    *.zip'''
              }
            }

            versionToUpgradeNetwork = 'v77.7.7-jenkins-visor-pup-' + currentBuild.number
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

      stage('generate network config') {
        environment {
          PATH = "${networkPath}:${env.PATH}"
          TESTS_DIR = "${testNetworkDir}"
          VEGACAPSULE_CONFIG_FILENAME = "${env.WORKSPACE}/system-tests/vegacapsule/${params.CAPSULE_CONFIG}"
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_REPOSITORY = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? config.protocolUpgradeReleaseRepository : ''}"
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_VERSION = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? params.BUILD_PROTOCOL_UPGRADE_VERSION : ''}"
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
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_REPOSITORY = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? config.protocolUpgradeReleaseRepository : ''}"
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_VERSION = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? params.BUILD_PROTOCOL_UPGRADE_VERSION : ''}"
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
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_REPOSITORY = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? config.protocolUpgradeReleaseRepository : ''}"
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_VERSION = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? params.BUILD_PROTOCOL_UPGRADE_VERSION : ''}"
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
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_REPOSITORY = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? config.protocolUpgradeReleaseRepository : ''}"
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_VERSION = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? params.BUILD_PROTOCOL_UPGRADE_VERSION : ''}"
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
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_REPOSITORY = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? config.protocolUpgradeReleaseRepository : ''}"
          PROTOCOL_UPGRADE_EXTERNAL_RELEASE_VERSION = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? params.BUILD_PROTOCOL_UPGRADE_VERSION : ''}"
          SYSTEM_TESTS_NETWORK_PARAM_OVERRIDES = "${params.BUILD_PROTOCOL_UPGRADE_VERSION ? params.BUILD_PROTOCOL_UPGRADE_VERSION : ''}"
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
            sleep '30'
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
          script {
            if (params.BUILD_PROTOCOL_UPGRADE_VERSION) {
              withGHCLI('credentialsId': vegautils.getVegaCiBotCredentials()) {
                sh '''gh release delete \
                    --yes \
                    --repo ''' + config.protocolUpgradeReleaseRepository + ''' \
                    ''' + protocolUpgradeVersion + ''' \
                | echo "Release does not exist"'''
              }
            }
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

        dir(testNetworkDir) {
          archiveArtifacts(
            artifacts: 'testnet/**/*',
            excludes: [
              'testnet/**/*.sock',
              'testnet/data/**/state/data-node/**/*',
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
