/* groovylint-disable DuplicateMapLiteral, DuplicateNumberLiteral, DuplicateStringLiteral, Indentation, LineLength, MethodSize, NestedBlockDepth */
void templateConfigs(String envName, String netHome, String tmplHome, String additionalFlags) {
  sh label: 'generate templates: ' + envName, script: """
        vegacapsule template genesis \
            --home-path '""" + netHome + """' \
            --path '""" + tmplHome + """/genesis.tmpl.json' """ + additionalFlags + """

        vegacapsule template node-sets \
            --home-path '""" + netHome + """' \
            --path '""" + tmplHome + """/vega.validators.tmpl.toml' \
            --nodeset-group-name validators \
            --type vega \
            --with-merge """ + additionalFlags + """

        vegacapsule template node-sets \
            --home-path '""" + netHome + """' \
            --path '""" + tmplHome + """/tendermint.common.tmpl.toml' \
            --nodeset-group-name validators \
            --type tendermint \
            --with-merge """ + additionalFlags + """

        vegacapsule template node-sets \
            --home-path '""" + netHome + """' \
            --path '""" + tmplHome + """/vega.full.tmpl.toml' \
            --nodeset-group-name full \
            --type vega \
            --with-merge """ + additionalFlags + """

        vegacapsule template node-sets \
            --home-path '""" + netHome + """' \
            --path '""" + tmplHome + """/tendermint.common.tmpl.toml' \
            --nodeset-group-name full \
            --type tendermint \
            --with-merge """ + additionalFlags + """

        vegacapsule template node-sets \
            --home-path '""" + netHome + """' \
            --path '""" + tmplHome + """/data_node.full.tmpl.toml' \
            --nodeset-group-name full \
            --type data-node \
            --with-merge """ + additionalFlags
}

void getReleasedBinary(String ghCredentialsID, String binType, String version) {
  withGHCLI('credentialsId': ghCredentialsID) {
        sh 'gh release --repo vegaprotocol/vega download ' + version + ' --pattern "' + binType + '-linux*"'
  }

  sh 'mv vega-linux-amd64 bin/' + binType
  sh 'chmod +x bin/' + binType
  sh binType + ' version'
}

void compileCoreBinary(String ghCredentialsID, String binType, String version, String outDir) {
  Map packagesMapping = [
        'vega': 'cmd/vega/',
        'vegawallet': 'cmd/vegawallet/',
        'data-node': 'cmd/data-node/'
    ]

  if (!packagesMapping.containsKey(binType)) {
    error('[compileBinary] invalid binary type. got: "' + binType + '"')
  }

  gitClone([
      url: 'git@github.com:vegaprotocol/vegacapsule.git',
      branch: 'main',
      directory: version,
      credentialsId: ghCredentialsID
  ])
  sh label: 'Building ' + binType,
        script: 'go mod vendor && go build -o ' + outDir + '/' + binType + ' ' + packagesMapping[binType]
}

void downloadS3Binary(String s3Path, String binName, String outDir) {
  sh label: 'Download the ' + binName + ' from the S3',
       script: 'aws s3 cp --only-show-errors --no-progress "' + s3Path + '" "' + outDir + '/' + binName + '"'
  sh label: 'Set permissions on the ' + binName + ' binary',
       script: 'chmod a+x "' + outDir + '/' + binName + '"'
}

boolean isS3Link(String path) {
    /* groovylint-disable-next-line DuplicateNumberLiteral */
  return path.length() > 0 && path[0..4] == 's3://'
}

void veganetSh(String network, String command, String sshCredentialsId, String dockerCredentialsId) {
  dir('devops-infra') {
    withCredentials([sshUserPrivateKey(
        credentialsId: sshCredentialsId,
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )]) {
      withDockerRegistry([
          credentialsId: dockerCredentialsId,
          url: 'https://ghcr.io'
      ]) {
        withGoogleSA('gcp-k8s') {
          sh label: 'Create markets',
            script: './veganet.sh ' + network + ' ' + command
        }
      }
    }
  }
}

void call(Map customConfig = [:]) {
  Map config = [
    networkName: '',
    nomadAddress: '',
    awsRegion: '',
    vegacapsuleS3BucketName: '',
    networksInternalBranch: 'main',
    nomadNodesNumer: 0,
  ] + customConfig

  pipeline {
    agent any
    environment {
      // TO-DO: Add secrets to jenkins
      NOMAD_TLS_SERVER_NAME = 'server.global.nomad'
      NOMAD_CACERT = credentials('nomad-cacert')
      NOMAD_CLIENT_CERT = credentials('nomad-client-cert')
      NOMAD_CLIENT_KEY = credentials('nomad-client-key')
      NOMAD_ADDR = "${config.nomadAddress}"

      AWS_SECRET_ACCESS_KEY = credentials('jenkins-vegacapsule-aws-secret')
      AWS_ACCESS_KEY_ID = credentials('jenkins-vegacapsule-aws-id')
      S3_BUCKET_NAME = "${config.vegacapsuleS3BucketName}"
      AWS_REGION = "${config.awsRegion}"

      PATH = "${env.WORKSPACE}/bin:${env.PATH}"
      GITHUB_CREDS = 'github-vega-ci-bot-artifacts'
      GITHUB_SSH_CREDS = 'vega-ci-bot'
      NETWORK_SSH_CREDENTIALS = 'ubuntu-ansible-key'
    }

    stages {
      stage('Prepare') {
        steps {
          script {
            print('Parameters: ' + params)

            sh label: 'Prepare binaries directory', script: 'mkdir -p bin'
          }
        }
      }

      stage('Init binaries and tools') {
        options {
          timeout(10)
        }

        parallel {
          stage('Check out vegaprotocol/networks-internal') {
            when {
              expression {
                params.REGENERATE_CONFIGS
              }
            }

            steps {
              gitClone([
                  credentialsId: env.GITHUB_SSH_CREDS,
                  url: 'git@github.com:vegaprotocol/networks-internal.git',
                  branch: config.networksInternalBranch,
                  directory: 'networks-internal'
              ])
            }
          }

          stage('Build capsule') {
            when {
              expression {
                params.BUILD_CAPSULE
              }
            }

            steps {
              gitClone([
                  credentialsId: env.GITHUB_SSH_CREDS,
                  url: 'git@github.com:vegaprotocol/vegacapsule.git',
                  branch: params.VEGACAPSULE_VERSION,
                  directory: 'vegacapsule'
              ])

              dir('vegacapsule') {
                sh 'go build -o vegacapsule .'
                sh 'mv vegacapsule ../bin'
              }
              sh 'chmod +x bin/vegacapsule'
              sh 'vegacapsule --help'
            }
          }

          stage('Download capsule') {
            when {
              not {
                expression {
                  params.BUILD_CAPSULE
                }
              }
            }
            steps {
              withGHCLI('credentialsId': env.GITHUB_CREDS) {
                sh 'gh release --repo vegaprotocol/vegacapsule download ' + params.VEGACAPSULE_VERSION + ' --pattern "*linux*"'
              }
              sh 'unzip vegacapsule-linux-amd64.zip'
              sh 'mv vegacapsule bin/'
              sh 'chmod +x bin/vegacapsule'
              sh 'vegacapsule --help'
            }
          }

          stage('Download vega binary from Github Release') {
            when {
              expression {
                !isS3Link(params.VEGA_VERSION) && !params.BUILD_VEGA_BINARY
              }
            }

            steps {
              getReleasedBinary(env.GITHUB_CREDS, 'vega', params.VEGA_VERSION)
            }
          }

          stage('Download vega binary from S3') {
            when {
              expression {
                isS3Link(params.VEGA_VERSION) && !params.BUILD_VEGA_BINARY
              }
            }

            steps {
              downloadS3Binary(params.VEGA_VERSION, 'vega', env.WORKSPACE + '/bin')
            }
          }

          stage('Build vega binary') {
            when {
              expression {
                params.BUILD_VEGA_BINARY
              }
            }

            steps {
              dir('core-vega') {
                compileCoreBinary(env.GITHUB_SSH_CREDS, 'vega', params.VEGA_VERSION, '../bin')
              }
            }
          }

          stage('Download data-node binary from Github Release') {
            when {
              expression {
                !isS3Link(params.DATA_NODE_VERSION) && params.BUILD_DATA_NODE_BINARY
              }
            }

            steps {
              getReleasedBinary(env.GITHUB_CREDS, 'data-node', params.DATA_NODE_VERSION)
            }
          }

          stage('Download data-node binary from S3') {
            when {
              expression {
                isS3Link(params.DATA_NODE_VERSION) && !params.BUILD_DATA_NODE_BINARY
              }
            }

            steps {
              downloadS3Binary(params.DATA_NODE_VERSION, 'data-node', env.WORKSPACE + '/bin')
            }
          }

          stage('Build  data-node binary') {
            when {
              expression {
                params.BUILD_DATA_NODE_BINARY
              }
            }

            steps {
              dir('core-vega') {
                compileCoreBinary(env.GITHUB_SSH_CREDS, 'data-node', params.DATA_NODE_VERSION, '../bin')
              }
            }
          }

          stage('Pull devops-infra') {
            steps {
              gitClone([
                  credentialsId: env.GITHUB_SSH_CREDS,
                  url: 'git@github.com:vegaprotocol/devops-infra.git',
                  branch: 'stagnet3-markets',
                  directory: 'devops-infra'
              ])
            }
          }
        }
      }

      stage('Sync remote state to local') {
        options {
          timeout(5)
        }

        steps {
          dir('networks-internal/' + config.networkName + '/vegacapsule') {
            sh '''
                mkdir -p "./home"
                aws s3 sync \
                --only-show-errors \
                --no-progress \
                  "s3://''' + env.S3_BUCKET_NAME + '''/''' + config.networkName + '''" \
                  "./home/"
            '''
            // sh "if [ -d '${env.CONFIG_HOME}/config.hcl' ]; then cat '${env.CONFIG_HOME}/config.hcl'; fi"
          }
        }
      }

      stage('Stop Network') {
        options {
          timeout(10)
        }

        when {
          expression {
            params.ACTION == 'STOP' || params.ACTION == 'RESTART'
          }
        }
        steps {
          dir('networks-internal/' + config.networkName + '/vegacapsule') {
            // Hotfix for https://github.com/vegaprotocol/vegacapsule/issues/228
            sh '''nomad job status --short \
              | grep -v Type \
              | grep running \
              | awk '{ print $1 }' \
              | xargs -L 1 nomad job stop'''
            sh "vegacapsule network destroy --home-path './home'"
          }
        }
      }

      stage('Update networks configs') {
        options {
          timeout(10)
        }

        when {
          expression {
            params.REGENERATE_CONFIGS
          }
        }
        stages {
                    // Will be enabled in future
                    // stage('Template s3 config') {
                    //     environment {
                    //         FLAGS = '--update-network'
                    //     }
                    //     steps {
                    //         script {
                    //             templateConfigs('s3', env.CONFIG_HOME, env.TEMPLATES_HOME, env.FLAGS)
                    //         }
                    //     }
                    // }

          stage('regenerate configs') {
            steps {
              dir('networks-internal/' + config.networkName + '/vegacapsule') {
                sh "ls -als \"${env.WORKSPACE}/bin\""
                sh "echo $PATH"
                sh '''vegacapsule network generate \
                                --force \
                                --config-path ./config.hcl \
                                --home-path ./home'''

                sh '''vegacapsule network keys import \
                                --keys-file-path  ./network.json \
                                --home-path ./home'''
              }
            }
          }

          stage('Template live config (git / networks-internal)') {
            environment {
              FLAGS = "--out-dir '${env.WORKSPACE}/networks-internal/${config.networkName}/live-config'"
            }
            steps {
              script {
                dir('networks-internal/' + config.networkName + '/vegacapsule') {
                  templateConfigs(
                    'live config (git / networks-internal)',
                    './home',
                    './config',
                    '--out-dir "./../live-config"'
                  )
                }
              }
            }
          }

          stage('Write to git') {
            steps {
              makeCommit(
                  makeCheckout: false,
                  directory: 'networks-internal',
                  branchName: 'live-config-update',
                  commitMessage: '[Automated] live config update for ' + config.networkName,
              )
            }
          }
        }
      }

      stage('Remove the network files') {
        options {
          timeout(20)
        }

        when {
          expression {
            (params.ACTION == 'RESTART' || params.ACTION == 'START') && params.UNSAFE_RESET_ALL
          }
        }
        steps {
          script {
            // TODO: Needs to be updated when the capsule feature is merged
            withCredentials([sshUserPrivateKey(
                credentialsId: env.NETWORK_SSH_CREDENTIALS,
                keyFileVariable: 'PSSH_KEYFILE',
                usernameVariable: 'PSSH_USER'
            )]) {
              for (node in (1..config.nomadNodesNumer).toList()) {
                print('Clear the n0' + node + ' node')


                sh '''ssh \
                  -o "StrictHostKeyChecking=no" \
                  -i "''' + PSSH_KEYFILE + '''" \
                  ''' + PSSH_USER + '''@n0''' + node + '''.''' + config.networkName + '''.vega.xyz \
                    sudo rm -r /home/vega/.vega /home/vega/.tendermint /home/vega/.data-node || true'''
              }
            }
          // sh 'vegacapsule nodes unsafe-reset-all --remote --home-path "' + env.CONFIG_HOME + '"'
          }
        }
      }
      stage('Start Network') {
        options {
          timeout(10)
        }

        when {
          expression {
            params.ACTION == 'START' || params.ACTION == 'RESTART'
          }
        }
        steps {
          dir('networks-internal/' + config.networkName + '/vegacapsule') {
            sh "vegacapsule network start --home-path './home' --do-not-stop-on-failure"
          }
        }
      }
      stage('Write to s3') {
        options {
          timeout(5)
          retry(3)
        }
        steps {
          dir('networks-internal/' + config.networkName + '/vegacapsule') {
            sh label: 'Remove old network data from S3', script: """
            aws s3 rm \
               --recursive \
               --only-show-errors \
               's3://""" + env.S3_BUCKET_NAME + """/""" + config.networkName + """'
            """
            sh label: 'Sync configs to s3', script: """
                aws s3 cp \
                --recursive \
                --only-show-errors \
                --no-progress \
                  './home/' \
                  's3://""" + env.S3_BUCKET_NAME + """/""" + config.networkName + """'
            """
          }
        }
      }

      stage('Create markets') {
        options {
          timeout(40)
        }

        steps {
          veganetSh(
            config.networkName,
            'create_markets',
            env.NETWORK_SSH_CREDENTIALS,
            env.GITHUB_CREDS,
          )
        }
      }

      stage('Bounce bots') {
        options {
          retry(3)
          timeout(20)
        }

        steps {
          veganetSh(
            config.networkName,
            'bounce_bots',
            env.NETWORK_SSH_CREDENTIALS,
            env.GITHUB_CREDS,
          )
        }
      }
    }

    post {
      always {
        cleanWs()
      }
    }
  }
}
