import jenkins.model.*
import java.util.logging.Logger
def logger = Logger.getLogger("")
def installed = false
def initialized = false
def pluginParameter="trilead-api okhttp-api simple-theme-plugin git pipeline-input-step bootstrap5-api pipeline-model-api blueocean-github-pipeline instance-identity aws-java-sdk-ec2 build-timeout github pam-auth pipeline-model-definition hashicorp-vault-plugin workflow-cps ssh-credentials cloud-stats basic-branch-build-strategies jsch node-iterator-api token-macro workflow-step-api github-branch-source pipeline-stage-step git-client jenkins-design-language blueocean-rest gradle snakeyaml-api pipeline-stage-view ionicons-api test-results-analyzer checks-api mina-sshd-api-common blueocean-web font-awesome-api workflow-job popper2-api handy-uri-templates-2-api favorite cloudbees-bitbucket-branch-source blueocean-personalization view-job-filters ws-cleanup branch-api workflow-scm-step jquery3-api echarts-api lockable-resources pipeline-milestone-step htmlpublisher blueocean-events jaxb display-url-api junit durable-task aws-java-sdk-minimal blueocean-display-url apache-httpcomponents-client-4-api jakarta-activation-api pipeline-stage-tags-metadata configuration-as-code blueocean-jwt plugin-util-api data-tables-api envinject-api authorize-project workflow-durable-task-step commons-lang3-api authentication-tokens job-dsl scm-api slack github-checks cloudbees-folder pipeline-github pipeline-build-step monitoring pipeline-groovy-lib copyartifact blueocean-config pubsub-light workflow-api blueocean-commons ssh-slaves mina-sshd-api-core jjwt-api pipeline-github-lib blueocean-dashboard pipeline-model-extensions blueocean-i18n docker-commons mailer digitalocean-plugin parameterized-scheduler commons-text-api blueocean-autofavorite sse-gateway credentials-binding timestamper antisamy-markup-formatter blueocean-rest-impl git-server javax-activation-api ssh-steps ssh-agent variant git-parameter github-oauth blueocean-core-js blueocean-bitbucket-pipeline plain-credentials github-api jnr-posix-api blueocean-pipeline-editor matrix-auth workflow-basic-steps workflow-support script-security blueocean-pipeline-scm-api jobConfigHistory blueocean-git-pipeline pipeline-rest-api resource-disposer blueocean credentials rebuild workflow-aggregator envinject javax-mail-api matrix-project workflow-multibranch structs email-ext jdk-tool blueocean-pipeline-api-impl jquery bouncycastle-api sshd pipeline-graph-analysis jackson2-api aws-credentials ansicolor docker-workflow parameter-separator command-launcher caffeine-api pipeline-utility-steps jakarta-mail-api popper-api"
def plugins = pluginParameter.split()
logger.info("" + plugins)
def instance = Jenkins.getInstance()
def pm = instance.getPluginManager()
def uc = instance.getUpdateCenter()
plugins.each {
  logger.info("Checking " + it)
  if (!pm.getPlugin(it)) {
    logger.info("Looking UpdateCenter for " + it)
    if (!initialized) {
      uc.updateAllSites()
      initialized = true
    }
    def plugin = uc.getPlugin(it)
    if (plugin) {
      logger.info("Installing " + it)
    	def installFuture = plugin.deploy()
      while(!installFuture.isDone()) {
        logger.info("Waiting for plugin install: " + it)
        sleep(3000)
      }
      installed = true
    }
  }
}
if (installed) {
  logger.info("Plugins installed, initializing a restart!")
  instance.save()
  instance.restart()
}