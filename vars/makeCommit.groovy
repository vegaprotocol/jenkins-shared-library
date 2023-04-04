def call(args){
    creds = args.get('credentialsId', 'vega-ci-bot')
    sshagent(credentials: [creds]) {
        withGHCLI('credentialsId': args.get('ghCredentialsId', vegautils.getVegaCiBotCredentials())) {
            if (args.get('makeCheckout', true)) {
                gitClone(
                    [credentialsId: creds] + args
                )
            }
            // use pr & automated merge (--auto flag) to pass all required CI pipelines like linters if there's any on the repository to make sure we don't make errored automated commits
            dir(args.get('directory', '.')) {
                sh label: "sync configs to git", script: """
                    git config --global user.email "vega-ci-bot@vega.xyz"
                    git config --global user.name "vega-ci-bot"
                    branchName="\$(date +%d-%m-%Y--%H-%M)-${args.branchName}"
                    git checkout -b "\$branchName"
                    ${args.get('commitAction', '')}
                    git commit -am "${args.commitMessage}" || exit 0
                    git push -u origin "\$branchName"
                    prUrl="\$(gh pr create --title '${args.commitMessage}' --body '${env.BUILD_URL}')"
                    sleep 5
                    gh pr merge "\${prUrl}" --auto --delete-branch --squash
                """
            }
        }
    }
}