/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
void call(Map additionalConfig=[:], parametersOverride=[:]) {
  Map defaultConfig = [
    hooks: [:],
    agentLabel: 'system-tests-capsule',
    extraEnvVars: [:],
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
              [ name: params.ORIGIN_REPO, branch: params.VEGA_BRANCH ],
              [ name: 'vegaprotocol/system-tests', branch: params.SYSTEM_TESTS_BRANCH ],
              [ name: 'vegaprotocol/vegacapsule', branch: params.VEGACAPSULE_BRANCH ],
              [ name: 'vegaprotocol/vegatools', branch: params.VEGATOOLS_BRANCH ],
              [ name: 'vegaprotocol/devops-infra', branch: params.DEVOPS_INFRA_BRANCH ],
              [ name: 'vegaprotocol/devopsscripts', branch: params.DEVOPSSCRIPTS_BRANCH ],
            ]
            def reposSteps = repositories.collectEntries{value -> [
                value.name,
                {
                  gitClone([
                    url: 'git@github.com:' + value.name + '.git',
                    branch: value.branch,
                    directory: value.name.split('/')[1],
                    credentialsId: 'vega-ci-bot',
                    timeout: 2,
                  ])
                }
            ]}
            reposSteps['pull system tests image'] = {
                withDockerRegistry([credentialsId: 'github-vega-ci-bot-artifacts', url: 'https://ghcr.io']) {
                  sh "docker pull ghcr.io/vegaprotocol/system-tests:latest"
                  sh "docker tag ghcr.io/vegaprotocol/system-tests:latest system-tests:local"
                }
            }
            parallel reposSteps
            }
          }
        }

      stage('build binaries') {
        steps {
          script {
            def binaries = [
              [ repository: 'vegacapsule', name: 'vegacapsule', packages: './main.go' ],
              [ repository: 'vega', name: 'vega', packages: './cmd/vega' ],
              [ repository: 'vega', name: 'visor', packages: './cmd/visor' ],
              [ repository: 'vega', name: 'data-node', packages: './cmd/data-node' ],
              [ repository: 'vega', name: 'vegawallet', packages: './cmd/vegawallet' ],
              [ repository: 'devopsscripts', name: 'devopsscripts', packages: './' ],
              [ repository: 'vegatools', name: 'vegatools', packages: './'],
            ]
            parallel binaries.collectEntries{value -> [
              value.name,
              {
                vegautils.buildGoBinary(value.repository,  testNetworkDir + '/' + value.name, value.packages)
              }
            ]}
          }
        }
      }

      stage('build upgrade binaries') {
        steps {
          script {
            dir('vega') {
                sh label: 'Build upgrade version of vega binary for tests', script: """#!/bin/bash -e
                sed -i 's/"v0.*"/"v99.99.0+dev"/g' version/version.go
                """
            }
            def binaries = [
              [ repository: 'vega', name: 'vega-v99.99.0+dev', packages: './cmd/vega' ],
            ]
            parallel binaries.collectEntries{value -> [
              value.name,
              {
                vegautils.buildGoBinary(value.repository,  testNetworkDir + '/' + value.name, value.packages)
              }
            ]}
          }
        }
      }

      stage('start nomad') {
        steps {
          dir('system-tests') {
              sh 'cp ./vegacapsule/nomad_config.hcl' + ' ' + testNetworkDir + '/nomad_config.hcl'
          }
          dir(testNetworkDir) {
              sh 'daemonize -o ' + testNetworkDir + '/nomad.log -c ' + testNetworkDir + ' -p ' + testNetworkDir + '/vegacapsule_nomad.pid ' + testNetworkDir + '/vegacapsule nomad --nomad-config-path=' + testNetworkDir + '/nomad_config.hcl'
          }
        }
      }

      stage('prepare system tests and network') {
        parallel {
          stage('generate network config') {
            environment {
              PATH = "${env.PATH}:${networkPath}"
            }
            steps {
              script {
                dir(testNetworkDir) {
                  withCredentials([
                    usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
                  ]) {
                    sh 'echo -n "' + TOKEN + '" | docker login https://ghcr.io -u "' + USER + '" --password-stdin'
                  }
                  timeout(time: 3, unit: 'MINUTES') {

                    sh 'sudo cp ' + testNetworkDir + '/vega /usr/local/bin/vega'
                    sh 'sudo cp ' + testNetworkDir + '/vegacapsule /usr/local/bin/vegacapsule'

                    sh '''./vegacapsule network generate \
                      --config-path ''' + testNetworkDir + '''/../system-tests/vegacapsule/''' + params.CAPSULE_CONFIG + ''' \
                      --home-path ''' + testNetworkDir + '''/testnet
                    '''
                  }
                  // needed for soak test pipelines
                  echo "archive vega from: ${testNetworkDir}"
                  archiveArtifacts(
                    artifacts: "./vega",
                  )
                }
              }
            }
          }

          stage('build system-tests docker images') {
            options {
              timeout(time: 10, unit: 'MINUTES')
              retry(3)
            }
            steps {
              dir('system-tests/scripts') {
                sh 'make check'
                withDockerRegistry([credentialsId: 'github-vega-ci-bot-artifacts', url: 'https://ghcr.io']) {
                  sh 'make prepare-test-docker-image'
                  sh 'make build-test-proto'
                }
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

      stage('start network') {
        environment {
          PATH = "${env.PATH}:${networkPath}"
        }

        steps {
          script {
            dir(testNetworkDir) {
              try {
                timeout(time: 3, unit: 'MINUTES') {
                  sh '''./vegacapsule network start \
                    --home-path ''' + testNetworkDir + '''/testnet
                  '''
                }
              } finally {
                sh 'docker logout https://ghcr.io'
              }

              // sh './vegacapsule nodes ls-validators --home-path ' + testNetworkDir + '/testnet > ' + testNetworkDir + '/testnet/validators.json'
              sh 'mkdir -p ' + testNetworkDir + '/testnet/smartcontracts'
              sh './vegacapsule state get-smartcontracts-addresses --home-path ' + testNetworkDir + '/testnet > ' + testNetworkDir + '/testnet/smartcontracts/addresses.json'
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
          dir(testNetworkDir) {
            sh './vegacapsule ethereum wait && ./vegacapsule ethereum multisig init --home-path "' + testNetworkDir + '/testnet"'
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
          SYSTEM_TESTS_DEBUG= "${params.SYSTEM_TESTS_DEBUG}"
          VEGACAPSULE_BIN_LINUX="${testNetworkDir}/vegacapsule"
          VEGA_BIN_LINUX="${testNetworkDir}/vega"
          SYSTEM_TESTS_LOG_OUTPUT="${testNetworkDir}/log-output"
          PATH = "${env.PATH}:${networkPath}"
          VEGACAPSULE_CONFIG_FILENAME = "/workspace/vegacapsule/${params.CAPSULE_CONFIG}"
        }

        steps {
          withCredentials([
            usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'GITHUB_API_TOKEN', usernameVariable:'GITHUB_API_USER')
          ]) {
            script {
              Map runStages = [
                'run system-tests': {
                  dir('system-tests/scripts') {
                    sh 'make test'
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
            parallel pipelineHooks.postRunTests
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
            int upgradeProposalOffset = 100
            def getLastBlock = { boolean silent ->
              return vegautils.shellOutput('''devopsscripts vegacapsule last-block \
                  --output value-only \
                  --network-home-path ''' + testNetworkDir + '''/testnet \
                  --local
                ''', silent).toInteger()
            }
            int initNetworkHeight = getLastBlock(false)
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
                --release-tag v0.990.0
            '''

            print('Waiting on block ' + proposalBlock)
            waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
                int currentNetworkHeight = getLastBlock(true)
                print('... still waiting, network heigh is ' + currentNetworkHeight)
                return (currentNetworkHeight >= proposalBlock)
            }
            initNetworkHeight = getLastBlock(false)
            print('Current network heigh is ' + initNetworkHeight)

            String dataNodeURL = vegautils.shellOutput('''devopsscripts vegacapsule info \
              --type data-node-grpc-url \
              --output value-only \
              --print-only-one \
              --network-home-path ''' + testNetworkDir + '''/testnet \
              --local
            ''')

            String validatorHomePath = vegautils.shellOutput('''devopsscripts vegacapsule info \
              --type validator-vega-home-dir \
              --output value-only \
              --print-only-one \
              --network-home-path ''' + testNetworkDir + '''/testnet \
              --local
            ''')

            print('Run snapshot checks')
            sleep '30'
            sh '''
              mkdir -p ./snapshot-tmp;
              rsync -av ''' + validatorHomePath + '''/state/node/snapshots/ ./snapshot-tmp;
              ls -als ./snapshot-tmp;
            '''

            try {
              sh '''vegatools difftool \
                -s "./snapshot-tmp" \
                -d "''' + dataNodeURL + '''"'''
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
            sh './vegacapsule network stop --home-path ' + testNetworkDir + '/testnet'
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
              'testnet/data/**/state/data-node/**/*'
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
            name: 'System Tests Capsule',
            channel: '#qa-notify',
            branch: 'st:' + params.SYSTEM_TESTS_BRANCH + ' | vega:' + params.VEGA_BRANCH
          )
        }
        cleanWs()
      }
    }
  }
}