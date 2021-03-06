/* groovylint-disable 
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral, 
  FactoryMethodName, VariableTypeRequired */
void buildGoBinary(String directory, String outputBinary, String packages) {
  timeout(time: 5, unit: 'MINUTES') {
    dir(directory) {
      // sh 'go mod vendor'
      sh 'go build -o ' + outputBinary + ' ' + packages
    }
  }
}

def boxPublicIP() {
    def boxIp = "unknown";
    try {
        boxIp = sh(script: 'curl ifconfig.co', returnStdout:true).trim()
    } catch(err) {
      // TODO: Add fallback to other services or linux commands
      print("Cannot get the box IP: " + err)
    }
    
    return boxIp
}

void call(Map additionalConfig) {
  def defaultCconfig = [
    branchDevopsInfra: 'master',
    branchVegaCapsule: pipelineDefaults.capsuleSystemTests.branchVegaCapsule,
    branchVega: 'develop',
    branchDataNode: 'develop',
    branchSystemTests: 'develop',
    branchVegawallet: 'develop',
    branchProtos: 'develop',
    branchVegatools: 'develop',
    
    systemTestsTestFunction: '',
    systemTestsTestMark: 'smoke',
    systemTestsTestDirectory: '',
    systemTestsDebug: '',

    capsuleConfig: 'capsule_config.hcl',

    preapareSteps: {},
    gitCredentialsId: 'vega-ci-bot',
    ignoreFailure: false,
    systemTestsRunTimeout: 60,
    printNetworkLogs: false,

    dockerCredentialsId: 'github-vega-ci-bot-artifacts',
  ]
  
  def config = defaultCconfig + additionalConfig

  print "config: " + config

  def testNetworkDir
  dir(pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
    testNetworkDir = pwd()
  }

  stage('prepare') {
    def publicIP = boxPublicIP()

    print("The box public IP is: " + publicIP)
    print("You may want to visit the nomad web interface: http://" + publicIP + ":4646")
    print("The nomad interface is available only when the tests are running")

    cleanWs()
    
    config.preapareSteps()
  }

  stage('get source codes') {
    def repositories = [ 
      [ name: 'devops-infra', branch: config.branchDevopsInfra ],
      [ name: 'vegacapsule', branch: config.branchVegaCapsule ],
      [ name: 'vega', branch: config.branchVega ],
      [ name: 'data-node', branch: config.branchDataNode ],
      [ name: 'system-tests', branch: config.branchSystemTests ],
      [ name: 'protos', branch: config.branchProtos ],
      [ name: 'vegatools', branch: config.branchVegatools ],
    ]

    parallel repositories.collectEntries{value -> [value.name, { 
      gitClone([
        url: 'git@github.com:vegaprotocol/' + value.name + '.git',
        branch: value.branch,
        directory: value.name,
        credentialsId: config.gitCredentialsId,
        timeout: 2,
      ])
    }]}
  }

  stage('build binaries') {
    def binaries = [
      [ repository: 'vegacapsule', name: 'vegacapsule', packages: './main.go' ],
      [ repository: 'vega', name: 'vega', packages: './cmd/vega/' ],
      [ repository: 'data-node', name: 'data-node', packages: './cmd/data-node/' ],
      [ repository: 'vega', name: 'vegawallet', packages: './cmd/vegawallet' ],
    ]
    
    parallel binaries.collectEntries{value -> [value.name, { buildGoBinary(value.repository,  testNetworkDir + '/' + value.name, value.packages) }]}
  }
  
  stage('start nomad') {
    dir('system-tests'){
        sh 'cp ./vegacapsule/nomad_config.hcl' + ' ' + testNetworkDir + '/nomad_config.hcl'
    }
    dir (testNetworkDir) {
        sh 'daemonize -o ' + testNetworkDir + '/nomad.log -c ' + testNetworkDir + ' -p ' + testNetworkDir + '/vegacapsule_nomad.pid ' + testNetworkDir + '/vegacapsule nomad --nomad-config-path=' + testNetworkDir + '/nomad_config.hcl'
    }
  }

  stage('prepare system tests and network') {
    def prepareSteps = [:]
    prepareSteps['prepare multisig setup script'] = {
      stage('prepare network config') {
        dir('system-tests') {
          sh 'cp ./vegacapsule/' + config.capsuleConfig + ' ' + testNetworkDir + '/config_system_tests.hcl'
        }
      }
    }

    prepareSteps['build system-tests docker images'] = {
      stage('build system-tests docker images') {
        dir('system-tests/scripts') {
          timeout(time: 5, unit: 'MINUTES') {
            ansiColor('xterm') {
              sh 'make check'

              withDockerRegistry([credentialsId: config.dockerCredentialsId, url: 'https://ghcr.io']) {
                sh 'make prepare-test-docker-image'
                sh 'make build-test-proto'
              }
            }
          }
        }
      }
    }

    prepareSteps['start the network'] = {
      stage('start the network') {
        dir(testNetworkDir) {
          try {
            withCredentials([
              usernamePassword(credentialsId: config.dockerCredentialsId, passwordVariable: 'TOKEN', usernameVariable:'USER')
            ]) {
              sh 'echo -n "' + TOKEN + '" | docker login https://ghcr.io -u "' + USER + '" --password-stdin'
            }
            timeout(time: 5, unit: 'MINUTES') {
              ansiColor('xterm') {
                sh './vegacapsule network bootstrap --config-path ./config_system_tests.hcl --home-path ' + testNetworkDir + '/testnet'
              }
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

    parallel prepareSteps
  }


  stage('setup multisig contract') {
    dir (testNetworkDir) {
      timeout(time: 2, unit: 'MINUTES') {
        ansiColor('xterm') {
          sh './vegacapsule ethereum wait && ./vegacapsule ethereum multisig init --home-path "' + testNetworkDir + '/testnet"'
        }
      }
    }
  }

  try {
    stage('run tests') {
      dir('system-tests/scripts') {
        withEnv([
            'TESTS_DIR=' + testNetworkDir,
            'NETWORK_HOME_PATH="' + testNetworkDir + '/testnet"',
            'TEST_FUNCTION="' + config.systemTestsTestFunction + '"',
            'TEST_MARK="' + config.systemTestsTestMark + '"',
            'TEST_DIRECTORY="' + config.systemTestsTestDirectory + '"',
            'USE_VEGACAPSULE=true',
            'SYSTEM_TESTS_DEBUG=' + config.systemTestsDebug,
            'VEGACAPSULE_BIN_LINUX="' + testNetworkDir + '/vegacapsule"',
            'SYSTEM_TESTS_LOG_OUTPUT="' + testNetworkDir + '/log-output"'
        ]) {
            ansiColor('xterm') {
              timeout(time: config.systemTestsRunTimeout, unit: 'MINUTES') {
                sh 'make test'
              }
            }
        }
      }
    }
  } finally {
    stage('Stop network') {
      dir(testNetworkDir) {
        sh './vegacapsule network stop --home-path ' + testNetworkDir + '/testnet'
      }
    }

    stage('Archive network logs') {
      dir(testNetworkDir) {
        if (config.printNetworkLogs) {
          sh './vegacapsule network logs --home-path ' + testNetworkDir + '/testnet | tee ./testnet/network.log'
        } else {
          sh './vegacapsule network logs --home-path ' + testNetworkDir + '/testnet > ./testnet/network.log'
        }
      }
    }

    stage('Post-steps') {
      dir(testNetworkDir) {
        archiveArtifacts artifacts: 'testnet/**/*',
                  allowEmptyArchive: true

        if (fileExists('log-output')) {
          archiveArtifacts artifacts: 'log-output/**/*',
                    allowEmptyArchive: true
        }
      }
      dir('system-tests') {
        archiveArtifacts artifacts: 'build/test-reports/**/*',
                  allowEmptyArchive: true
        archiveArtifacts artifacts: 'test_logs/**/*',
                  allowEmptyArchive: true
      
        junit checksName: 'System Tests',
          testResults: 'build/test-reports/system-test-results.xml',
          skipMarkingBuildUnstable: config.ignoreFailure,
          skipPublishingChecks: config.ignoreFailure
      }
      archiveArtifacts artifacts: pipelineDefaults.art.systemTestCapsuleJunit,
                  allowEmptyArchive: true

      slack.slackSendCIStatus name: 'System Tests Capsule',
        channel: '#qa-notify',
        branch: 'st:' + config.branchSystemTests + ' | vega:' + config.branchVega 
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