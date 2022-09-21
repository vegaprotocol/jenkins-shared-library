void call(Map additionalConfig=[:], customParameters=[:]) {
  Map defaultConfig = [
    postNetworkGenerateStages: [:],
    postNetworkStartStages: [:],
    systemTestsSiblingsStages: [:],
    vegacapsuleConfig: params.CAPSULE_CONFIG,

    systemTestsBranch: params.SYSTEM_TESTS_BRANCH,
  ]
  Map config = defaultConfig + additionalConfig
  params = params + customParameters

  pipeline {
    agent {
      label 'test-instance'
    }
    
    options {
      ansiColor('xterm')
      timestamps()
    }
    stages {
      stage('prepare') {
        steps {
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
              print("PATH = " + env.PATH)
              print("networkPath = " + networkPath)

            }
          }
        }
      }

      stage('get source codes') {
        steps {
          script {
            def repositories = [
              [ name: params.ORIGIN_REPO, branch: params.VEGA_BRANCH ],
              [ name: 'vegaprotocol/system-tests', branch: config.systemTestsBranch ],
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
          stage('start the network') {
            environment {
              PATH = "${networkPath}:${env.PATH}"
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
                      sh '''./vegacapsule network generate \
                        --config-path ''' + testNetworkDir + '''/../system-tests/vegacapsule/''' + config.vegacapsuleConfig + ''' \
                        --home-path ''' + testNetworkDir + '''/testnet
                      '''
                    }
                }

                if (config.containsKey('postNetworkGenerateStages') && config.postNetworkGenerateStages.size() > 0) {
                  vegautils.runSteps config.postNetworkGenerateStages
                }

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
                  
                  sh './vegacapsule nodes ls-validators --home-path ' + testNetworkDir + '/testnet > ' + testNetworkDir + '/testnet/validators.json'
                  sh 'mkdir -p ' + testNetworkDir + '/testnet/smartcontracts'
                  sh './vegacapsule state get-smartcontracts-addresses --home-path ' + testNetworkDir + '/testnet > ' + testNetworkDir + '/testnet/smartcontracts/addresses.json'
                }
              }
            }
          }

          stage('build system-tests docker images') {
            options {
              timeout(time: 5, unit: 'MINUTES')
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
        environment {
          PATH = "${networkPath}:${env.PATH}"
        }

        options {
          timeout(time: 10, unit: 'MINUTES')
        }

        steps {
          script {
            if (config.containsKey('postNetworkStartStages') && config.postNetworkStartStages.size() > 0) {
              parallel config.postNetworkStartStages
            }
          }
        }
      }

      stage('run tests') {
        options {
          timeout(time: params.TIMEOUT, unit: 'MINUTES')
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
          SYSTEM_TESTS_LOG_OUTPUT="${testNetworkDir}/log-output"
        }

        steps {
          script {
            Map runStages = [
              'run system-tests': {
                dir('system-tests/scripts') {
                    sleep 36000
                    sh 'make test'
                }
              }
            ]
            if (config.containsKey('systemTestsSiblingsStages') && config.systemTestsSiblingsStages.size() > 0) {
              runStages = runStages + config.systemTestsSiblingsStages
            }

            parallel runStages
          }
        }
      }
    }

    post {
      always {
        catchError {
          dir(testNetworkDir) {
            sh './vegacapsule network stop --home-path ' + testNetworkDir + '/testnet'
          }
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

          dir(testNetworkDir) {
            archiveArtifacts(
              artifacts: 'testnet/**/*',
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
            junit(
              checksName: 'System Tests',
              testResults: 'build/test-reports/system-test-results.xml',
              skipMarkingBuildUnstable: false,
              skipPublishingChecks: false,
            )
          }
          archiveArtifacts(
            artifacts: pipelineDefaults.art.systemTestCapsuleJunit,
            allowEmptyArchive: true
          )
        }
        script {
          slack.slackSendCIStatus(
            name: 'System Tests Capsule',
            channel: '#qa-notify',
            branch: 'st:' + config.systemTestsBranch + ' | vega:' + params.VEGA_BRANCH
          )
        }
        cleanWs()
      }
    }
  }
}