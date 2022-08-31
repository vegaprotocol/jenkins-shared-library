/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
void buildGoBinary(String directory, String outputBinary, String packages) {
  timeout(time: 5, unit: 'MINUTES') {
    dir(directory) {
      // sh 'go mod vendor'
      sh "go build -o ${outputBinary} ${packages}"
      sh "chmod +x ${outputBinary}"
    }
  }
}

def boxPublicIP() {
    def commands = [
      'curl -4 icanhazip.com',
      'curl ifconfig.co',
      'curl ipinfo.io/ip',
      'curl api.ipify.org',
      'dig +short myip.opendns.com @resolver1.opendns.com',
      'curl ident.me',
      'curl ipecho.net/plain'
    ]

    for (it in commands) {
      try {
        boxIp = sh(script: it, returnStdout:true).trim()

        if (boxIp != "") {
          return boxIp;
        }
      } catch(err) {
        // TODO: Add fallback to other services or linux commands
        print("Cannot get the box IP with command " + it + " : " + err)
      }
    }

    return ""
}

void call(Map additionalConfig) {
  pipeline {
    agent {
      label 'system-tests-capsule'
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
              publicIP = boxPublicIP()
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
              [ name: 'vega', branch: params.VEGA_BRANCH ],
              [ name: 'system-tests', branch: params.SYSTEM_TESTS_BRANCH ],
              [ name: 'vegacapsule', branch: params.VEGACAPSULE_BRANCH ],
              [ name: 'vegatools', branch: params.VEGATOOLS_BRANCH ],
              [ name: 'devops-infra', branch: params.DEVOPS_INFRA_BRANCH ],
              [ name: 'devopsscripts', branch: params.DEVOPSSCRIPTS_BRANCH ],
            ]
            def steps = repositories.collectEntries{value -> [
                value.name,
                {
                  gitClone([
                    url: 'git@github.com:vegaprotocol/' + value.name + '.git',
                    branch: value.branch,
                    directory: value.name,
                    credentialsId: 'vega-ci-bot',
                    timeout: 2,
                  ])
                }
              ]}
            steps['pull system tests image'] = {
                withDockerRegistry([credentialsId: 'github-vega-ci-bot-artifacts', url: 'https://ghcr.io']) {
                  sh "docker pull ghcr.io/vegaprotocol/system-tests:latest"
                  sh "docker tag ghcr.io/vegaprotocol/system-tests:latest system-tests:local"
                }
            }
            parallel steps
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
            ]
            parallel binaries.collectEntries{value -> [
              value.name,
              {
                buildGoBinary(value.repository,  testNetworkDir + '/' + value.name, value.packages)
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

          stage('start the network') {
            steps {
              script {
                dir(testNetworkDir) {
                  try {
                    withCredentials([
                      usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
                    ]) {
                      sh 'echo -n "' + TOKEN + '" | docker login https://ghcr.io -u "' + USER + '" --password-stdin'
                    }
                    timeout(time: 5, unit: 'MINUTES') {
                      sh '''./vegacapsule network bootstrap \
                        --config-path ''' + testNetworkDir + '''/../system-tests/vegacapsule/''' + params.CAPSULE_CONFIG + ''' \
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
        }
      }

      stage('setup multisig contract') {
        options {
          timeout(time: 2, unit: 'MINUTES')
        }
        steps {
          dir(testNetworkDir) {
            sh './vegacapsule ethereum wait && ./vegacapsule ethereum multisig init --home-path "' + testNetworkDir + '/testnet"'
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
          dir('system-tests/scripts') {
              sh 'make test'
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
          dir(testNetworkDir) {
            archiveArtifacts(
              artifacts: 'testnet/**/*',
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
            branch: 'st:' + params.SYSTEM_TESTS_BRANCH + ' | vega:' + params.VEGA_BRANCH
          )
        }
        cleanWs()
      }
    }
  }
}

/**
 * Example usage
 */
// call([
//   systemTestsTestFunction: 'test_importWalletValidRecoverPhrase',
//   preapareSteps: {
//     // Move it to AMI, will be removed soon
//       sh 'sudo apt-get install -y daemonize'
//   }
// ])
