/* groovylint-disable 
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral, 
  FactoryMethodName, VariableTypeRequired */


void gitClone(String repository, String branch, String directory, String credentialsId) {
  dir(directory) {
    checkout([
      $class: 'GitSCM',
      branches: [[name: branch]],
      userRemoteConfigs: [[
          url: 'git@github.com:vegaprotocol/' + repository + '.git',
          credentialsId: credentialsId
    ]]])
  }
}

void buildGoBinary(String directory, String outputBinary, String packages) {
  dir(directory) {
    sh 'go mod vendor'
    sh 'go build -o ' + outputBinary + ' ' + packages
  }
}

void call(Map additionalConfig) {
  def defaultCconfig = [
    branchDevopsInfra: 'master',
    branchVegaCapsule: 'main',
    branchVega: 'develop',
    branchDataNode: 'develop',
    branchSystemTests: 'main',
    branchVegawallet: 'develop',
    branchProtos: 'develop',
    branchVegatools: 'develop',
    
    systemTestsTestFunction: '',
    systemTestsTestMark: 'smoke',
    systemTestsTestDirectory: '',
    systemTestsDebug: '',

    preapareSteps: {},
    nodeIdentifier: 'system-tests-capsule',
    gitCredentialsId: 'vega-ci-bot',
  ]
  
  def config = defaultCconfig + additionalConfig

  print "config: " + config

  node(config.nodeIdentifier) {
    def testDirectoryPath
    dir('tests') {
      testDirectoryPath = sh(returnStdout: true, script: 'pwd').trim()
    }

    stage('prepare') {
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
        [ name: 'vegawallet', branch: config.branchVegawallet ],
        [ name: 'protos', branch: config.branchProtos ],
        [ name: 'vegatools', branch: config.branchVegatools ],
      ]

      parallel repositories.collectEntries{value -> [value.name, { gitClone(value.name, value.branch, value.name, config.gitCredentialsId) }]}
    }

    stage('build binaries') {
      def binaries = [
        [ repository: 'vegacapsule', name: 'vegacapsule', packages: './main.go' ],
        [ repository: 'vega', name: 'vega', packages: './cmd/vega/' ],
        [ repository: 'data-node', name: 'data-node', packages: './cmd/data-node/' ],
        [ repository: 'vegawallet', name: 'vegawallet', packages: './main.go' ],
      ]
      
      parallel binaries.collectEntries{value -> [value.name, { buildGoBinary(value.repository,  testDirectoryPath + '/' + value.name, value.packages) }]}
    }
    
    stage('prepare network') {
      dir('system-tests') {
        sh 'cp -r ./vegacapsule/multisig-setup ' + testDirectoryPath
        sh 'cp ./vegacapsule/capsule_config.hcl ' + testDirectoryPath + '/config_system_tests.hcl'
      }

      dir ('tests/multisig-setup') {
        sh 'npm install'
      }
      
      dir ('tests') {
          sh 'daemonize -o ' + testDirectoryPath + '/nomad.log -c ' + testDirectoryPath + ' -p ' + testDirectoryPath + '/vegacapsule_nomad.pid ' + testDirectoryPath + '/vegacapsule nomad'
      }

      dir('system-tests/scripts') {
        sh 'make check'
        sh 'make prepare-test-docker-image'
        sh 'make build-test-proto'
      }
    }

    stage('start the network') {
      dir('tests') {
        try {
          withCredentials([
            usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
          ]) {
            sh 'echo -n "' + TOKEN + '" | docker login https://ghcr.io -u "' + USER + '" --password-stdin'
          }
          sh './vegacapsule network bootstrap --config-path ./config_system_tests.hcl --home-path ' + testDirectoryPath + '/testnet'
        } catch (e) {
          throw e
        } finally {
          sh 'docker login https://ghcr.io'
        }
        sh './vegacapsule nodes ls-validators --home-path ' + testDirectoryPath + '/testnet > ' + testDirectoryPath + '/testnet/validators.json'
        sh 'mkdir -p ' + testDirectoryPath + '/testnet/smartcontracts'
        sh './vegacapsule state get-smartcontracts-addresses --home-path ' + testDirectoryPath + '/testnet > ' + testDirectoryPath + '/testnet/smartcontracts/addresses.json'
      }
    }

    stage('setup multisig contract') {
      dir ('tests/multisig-setup') {
        sh 'node main.js "' + testDirectoryPath + '/testnet/smartcontracts/addresses.json" "' + testDirectoryPath + '/testnet/validators.json"'
      }
    }

    stage('run tests') {
          ansiColor('xterm') {
            try {
              dir('system-tests/scripts') {
                withEnv([
                  'NETWORK_HOME_PATH="' + testDirectoryPath + '/testnet"',
                  'TEST_FUNCTION="' + config.systemTestsTestFunction + '"',
                  'TEST_MARK="' + config.systemTestsTestMark + '"',
                  'TEST_DIRECTORY="' + config.systemTestsTestDirectory + '"',
                  'USE_VEGACAPSULE=true',
                  'SYSTEM_TESTS_DEBUG=' + config.systemTestsDebug,
                  'VEGACAPSULE_BIN_LINUX="' + testDirectoryPath + '/vegacapsule"',
                  'SYSTEM_TESTS_LOG_OUTPUT="' + testDirectoryPath + '/log-output"'
                ]) {
                  sh 'make test'
                }
              }
            } catch (e) {
              throw e
            } finally {
              dir('tests') {
                archiveArtifacts artifacts: 'testnet/**/*.*',
                          allowEmptyArchive: true

                if (fileExists('log-output')) {
                  archiveArtifacts artifacts: 'log-output/**/*.*',
                            allowEmptyArchive: true
                }
              }
              dir('system-tests') {
                archiveArtifacts artifacts: 'build/test-reports/**/*.*',
                          allowEmptyArchive: true
              }
            }
          }
    }

    stage('cleanup') {
      dir('tests') {
        cleanup(testDirectoryPath)
      }
    }
  }
}

/**
 * Example usage
 */
call([
  branchSystemTests: 'vega-capsule',
  systemTestsTestFunction: 'test_importWalletValidRecoverPhrase',
  preapareSteps: {
     // Move it to AMI, will be removed soon
      sh 'sudo apt-get install -y daemonize'
  }
])