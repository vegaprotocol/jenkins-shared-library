/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
/* groovylint-disable GStringAsMapKey */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(Map cfg=[:], Map steps=[:]) {
  final String unchangedStr = 'unchanged'
  final List availableNetworks = ['devnet', 'stagnet', 'stagnet2', 'testnet']
  final Map devopsScriptsNetworksRemap = [
    devnet: 'devnet1',
    stagnet: 'stagnet1',
    stagnet2: 'stagnet2',
    testnet: 'fairground',
  ]
  final Map defaultConfig = [
    sshCredentialsId: 'ssh-vega-network',
    githubAPICredentials: 'github-vega-ci-bot-artifacts',
    dockerCredentials: 'github-vega-ci-bot-artifacts',
    slackChannel: '#tradingcore-notify',

    devopsInfraBranch: 'master',
    ansibleBranch: 'master',
    devopsScriptsBranch: 'main',
    vegaBranch: 'main',
    network:'invalid',
    vegaCoreVersion: unchangedStr,
    dataNodeVersion: unchangedStr,
    recreateBotsWallets: false
  ]
  final Map defaultSteps = [
    approve: true,
    deployConfig: true,
    deployVegaCore: true,
    restartNetwork: true,
    loadCheckpoint: true,
    createMarkets: true,
    startBots: true
  ]

  Map config = defaultConfig + config
  Map runningSteps = defaultSteps + steps
  runningSteps.deployVegaCore = (config.vegaCoreVersion != unchangedStr)

  String vegaBinaryPath

  // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
  /* groovylint-disable-next-line NoDef, VariableTypeRequired */
  def sshCredentials = sshUserPrivateKey(credentialsId: config.sshCredentialsId,
                                                keyFileVariable: 'PSSH_KEYFILE',
                                                usernameVariable: 'PSSH_USER')
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
  def githubAPICredentials = usernamePassword(credentialsId: config.githubAPICredentials,
                                              passwordVariable: 'GITHUB_API_TOKEN',
                                              usernameVariable: 'GITHUB_API_USER')
  Map dockerCredentials = [credentialsId: config.dockerCredentials,
                           url: 'https://ghcr.io']

  try {
    stage('Git checkout') {
      parallel([
        'get devops-infra': {
          retry(3) {
            gitClone([
              directory: 'devops-infra',
              url: 'git@github.com:vegaprotocol/devops-infra.git',
              branch: config.devopsInfraBranch
            ])
          }
        },
        'get ansible': {
          retry(3) {
            gitClone([
              directory: 'ansible',
              url: 'git@github.com:vegaprotocol/ansible.git',
              branch: config.ansibleaBranch
            ])
          }
        },
        'get devopsscripts': {
          retry(3) {
            gitClone([
              directory: 'devopsscripts',
              url: 'git@github.com:vegaprotocol/devopsscripts.git',
              branch: config.devopsScriptsBranch
            ])
          }
        },
        'get vega': {
          retry(3) {
            gitClone([
              directory: 'vega',
              url: 'git@github.com:vegaprotocol/vega.git',
              branch: config.vegaBranch
            ])
          }
        }
      ])
    }

    stage('Status') {
      if (config.network !in availableNetworks) {
        error('Network is not supported')
      }

      withDockerRegistry(dockerCredentials) {
        withCredentials([sshCredentials]) {
          dir('devops-infra') {
            sh script: "./veganet.sh ${config.network} status"
          }
        }
      }
    }

    stage('Prepare') {
      parallel([
        'CI config': {
          // Printout all configuration variables
          sh 'printenv'
          echo "params=${params.inspect()}"
        },
        "${params.NETWORK}: config": {
          if (runningSteps.deployConfig == false) {
            print 'Ansible step is skipped'
            return
          }

          dir('ansible') {
            withCredentials([sshStagnetCredentials]) {
              sh label: 'ansible dry run', script: """#!/bin/bash -e
                  ansible-playbook \
                      --check --diff \
                      -u "\${PSSH_USER}" \
                      --private-key "\${PSSH_KEYFILE}" \
                      --inventory inventories \
                      --limit ${config.network} \
                      --tags vega-network-config \
                      site.yaml
              """
            }
          }
        },
        'build vega': {
          if (steps.loadCheckpoint == false) {
            print('no vega required for release')
            return
          }

          dir('vega') {
            vegaBinaryPath = sh(returnStdout: true, script: 'pwd').trim()
            sh 'go build -o vega ./cmd/vega/'
          }
        }
      ])
    }

    stage('Approve') {
      if (runningSteps.approve == false) {
        print 'Approve step skipped'
        Utils.markStageSkippedForConditional('Approve')
        return
      }

      // TODO: Limit who can approve and send public slack message to them
      timeout(time: 15, unit: 'MINUTES') {
        input message: """Deploy to "${config.network}" Network?\n
        |- Deploy Vega Core: '${config.vegaCoreVersion}'
        |- Deploy Data Node: '${config.dataNodeVersion}'
        |- Deploy Config (genesis etc): '${runningSteps.deployConfig ? 'yes' : 'no'}'
        |- Restart: '${runningSteps.restartNetwork}'
        |- Reason: \n"${config.reason}"
        |\nNote: You can view potential Network Config changes (e.g. genesis) in previous stage
        """.stripMargin(), ok: 'Approve'
      }
    }

    stage('Deploy vega core') {
      if (runningSteps.deployVegaCore == false) {
        print 'Deploy vega core skipped'
        Utils.markStageSkippedForConditional('Deploy vega core')
        return
      }

      withDockerRegistry(dockerCredentials) {
        withCredentials([githubAPICredentials, sshStagnetCredentials]) {
          dir('devops-infra') {
            sh script: "TAG='${config.vegaCoreVersion}' ./veganet.sh ${config.network} getvega"
          }
        }
      }
    }

    stage('Deploy Vega Network Config') {
      if (runningSteps.deployConfig == false) {
        print 'Deployment for vega network config skipped'
        Utils.markStageSkippedForConditional('Deploy Vega Network Config')
        return
      }
      dir('ansible') {
        withCredentials([sshStagnetCredentials]) {
          // Note: environment variables PSSH_KEYFILE and PSSH_USER
          //        are set by withCredentials wrapper
          sh label: 'ansible deploy run', script: """#!/bin/bash -e
              ansible-playbook \
                  -u "\${PSSH_USER}" \
                  --private-key "\${PSSH_KEYFILE}" \
                  --inventory inventories \
                  --limit ${config.network} \
                  --tags vega-network-config \
                  site.yaml
          """
        }
      }
    }

    stage('Stop the network') {
      if (runningSteps.restartNetwork == false) {
        echo 'Network restart is skipped'
        Utils.markStageSkippedForConditional('Stop the network')
        return
      }

      withDockerRegistry(dockerCredentials) {
        withCredentials([sshStagnetCredentials]) {
          dir('devops-infra') {
            sh script: './veganet.sh ' + config.network + ' stopstopbots stop'
          }
        }
      }
    }

    stage('Load checkpoint') {
      if (runningSteps.restartNetwork == false || runningSteps.loadCheckpoint == false) {
        echo 'Load checkpoint step is skipped'
        Utils.markStageSkippedForConditional('Load checkpoint')
        return
      }

      dir('devopsscripts') {
        deopsScriptsNetwork = devopsScriptsNetworksRemap[config.network]

        withCredentials([sshStagnetCredentials]) {
          sh 'go run main.go old-network remote load-latest-checkpoint ' +
              '--vega-binary "' + vegaBinaryPath + '" ' +
              '--network "' + deopsScriptsNetwork + '" ' +
              /* groovylint-disable-next-line GStringExpressionWithinString */
              '--ssh-private-key "\${PSSH_KEYFILE}" ' +
              /* groovylint-disable-next-line GStringExpressionWithinString */
              '--ssh-user "\${PSSH_USER}" ' +
              '--dry-run'
        }
      }
    }

    stage('Start the network') {
      if (runningSteps.restartNetwork == false) {
        echo 'Start the network skipped'
        Utils.markStageSkippedForConditional('Start the network')
        return
      }

      additionalVars = []
      if (config.dataNodeVersion != unchangedStr) {
        echo 'Data node version changed to ' + config.dataNodeVersion
        additionalVars = ['DATANODE_TAG=' + params.DEPLOY_DATA_NODE]
      }

      withDockerRegistry(dockerCredentials) {
        withCredentials([sshStagnetCredentials]) {
          dir('devops-infra') {
            sh script: additionalVars.join(' ') + ' ./veganet.sh testnet start_datanode start'
          }
        }
      }
    }

    stage('Create markets') {
      if (runningSteps.createMarkets == false) {
        echo 'Start the network is skipped'
        Utils.markStageSkippedForConditional('Create markets')
        return
      }

      withDockerRegistry(dockerCredentials) {
        withCredentials([sshStagnetCredentials]) {
          dir('devops-infra') {
            sh script: './veganet.sh ' + config.network + ' create_markets'
            sh script: './veganet.sh ' + config.network + ' incentive_create_markets'
          }
        }
      }
    }

    stage('Start bots') {
      if (runningSteps.startBots == false) {
        print 'Start bots skipped'
        Utils.markStageSkippedForConditional('Start bots')
        return
      }

      additionalVars = []
      if (config.recreateBotsWallets == false) {
        echo 'Skipping bots cresyion d'
        additionalVars = ['SKIP_REMOVE_BOT_WALLETS=true']
      }

      withDockerRegistry(dockerCredentials) {
        withCredentials([sshStagnetCredentials]) {
          dir('devops-infra') {
            sh script: additionalVars.join(' ') + ' ./veganet.sh testnet bounce_bots'
          }
        }
      }
    }
  } catch (FlowInterruptedException e) {
    currentBuild.result = 'ABORTED'
    throw e
  } catch (e) {
    // Workaround Jenkins problem: https://issues.jenkins.io/browse/JENKINS-47403
    // i.e. `currentResult` is not set properly in the finally block
    /* groovylint-disable-next-line LineLength */
    // CloudBees workaround: https://support.cloudbees.com/hc/en-us/articles/218554077-how-to-set-current-build-result-in-pipeline
    currentBuild.result = 'FAILURE'
    throw e
  } finally {
    stage('Cleanup') {
      if (currentBuild.result == 'SUCCESS') {
          String msg = "Successfully restarted `${config.network}`"
          if (runningSteps.deployVegaCore) {
              msg = "Successfully deployed `${config.vegaCoreVersion}` to `${config.network}`"
          }
          slackSend(
              channel: config.slackChannel,
              color: 'good',
              message: ":rocket: ${msg} :astronaut:",
          )
      } else {
          String msg = "Failed to restart `${config.network}`"
          if (runningSteps.deployVegaCore) {
              msg = "Failed to deploy `${config.vegaCoreVersion}` to `${config.network}`"
          }
          msg += ". Please check <${env.RUN_DISPLAY_URL}|CI logs> for details"
          slackSend(
              channel: config.slackChannel,
              color: 'danger',
              message: ":boom: ${msg} :scream:",
          )
      }
    }
  }
}
