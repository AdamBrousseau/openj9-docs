/*******************************************************************************
 * Copyright (c) 2020, 2020 IBM Corp. and others
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] http://openjdk.java.net/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0 WITH
 * Classpath-exception-2.0 OR LicenseRef-GPL-2.0 WITH Assembly-exception
 *******************************************************************************/

def parentBuildName = 'Pipeline-Build-Javadoc'
parentBuildName = 'Pipeline-Build-Test-Personal'
def java8BuildName = params.JAVA8_BUILD_NAME ?: 'Build_JDK8_s390x_linux_Personal'
def java11BuildName = params.JAVA11_BUILD_NAME ?: 'Build_JDK11_s390x_linux_Personal'
def builds = [
    [name: java8BuildName, number: params.JAVA8_BUILD_NUMBER],
    [name: java11BuildName, number: params.JAVA11_BUILD_NUMBER]
]




NAMESPACE = 'eclipse'
CONTAINER_NAME = 'openj9-docs'
ARCHIVE = 'doc.tar.gz'
HTTP = 'https://'
OPENJ9_REPO = 'github.com/eclipse/openj9-docs'
OPENJ9_STAGING_REPO = 'github.com/eclipse/openj9-docs-staging'
ECLIPSE_REPO = 'ssh://genie.openj9@git.eclipse.org:29418/www.eclipse.org/openj9/docs.git'
SSH_CREDENTIAL_ID = 'git.eclipse.org-bot-ssh'

BUILD_DIR = 'built_doc'
CREDENTIAL_ID = 'b6987280-6402-458f-bdd6-7affc2e360d4'



timeout(time: 6, unit: 'HOURS') {
    timestamps {
        node('worker') {
            try {
                // TODO: Get latest builds programatically
                /*
                def parentBuild = Jenkins.instance.getItemByFullName(parentBuildName)
                println parentBuild
                println parentBuild.class // org.jenkinsci.plugins.workflow.job.WorkflowJob
                def lastSuccess = parentBuild.getLastSuccessfulBuild()
                println lastSuccess
                println lastSuccess.class // org.jenkinsci.plugins.workflow.job.WorkflowRun
                */
                /*

                def buildEnv = lastSuccess.getBuildVariables()
                println buildenv
                println buildenv.class
                error('no')
                */
                println builds

                def TMP_DESC = (currentBuild.description) ? currentBuild.description + "<br>" : ""
                currentBuild.description = TMP_DESC + "<a href=${JENKINS_URL}computer/${NODE_NAME}>${NODE_NAME}</a>"

                for (build in builds) {
                    println build
                    println build['name']
                    println build['number']
                    def job =  Jenkins.instance.getItemByFullName(build['name'])
                    println job
                    def run = job.getBuildByNumber(build['number'].toInteger())
                    println run
                    println run.class
                    def runEnv = run.getEnvironmant()
                    println runEnv
                    println runEnv.class
                }

            } catch(e) {
                if (!params.ghprbPullId) {
                    // Set status on the Github commit for non PR builds
                    //setBuildStatus("${HTTP}${OPENJ9_REPO}", MERGE_COMMIT, 'FAILURE', 'Failed to build and push doc')
                    slackSend channel: '@adam.brousseau', color: 'danger', message: "Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
                }
                throw e
            } finally {
                cleanWs()
            }
        }
    }
}

def copy_built_doc(DIR) {
    sh "rm -rf *"
    sh "cp -r ${WORKSPACE}/${DIR}/site/* ."
}

def push_doc_with_cred(REPO, BRANCH, MESSAGE){
    withCredentials([usernamePassword(credentialsId: CREDENTIAL_ID, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
        push_doc("${HTTP}${USERNAME}:${PASSWORD}@${REPO}", BRANCH, MESSAGE)
    }
}
def push_doc(REPO, BRANCH, MESSAGE) {
    sh "git status"
    STATUS = sh (script: 'git status --porcelain', returnStdout: true).trim()
    if ("x${STATUS}" != "x") {
        sh 'git config user.name "genie-openj9"'
        sh 'git config user.email "openj9-bot@eclipse.org"'
        sh 'git add .'
        sh "git commit -sm '${MESSAGE}'"
        sh "git push ${REPO} ${BRANCH}"
    }
}

def setBuildStatus(REPO, SHA, STATE, MESSAGE) {
    step([
        $class: "GitHubCommitStatusSetter",
        reposSource: [$class: "ManuallyEnteredRepositorySource", url: REPO],
        contextSource: [$class: "ManuallyEnteredCommitContextSource", context: JOB_NAME],
        errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
        commitShaSource: [$class: "ManuallyEnteredShaSource", sha: SHA ],
        statusBackrefSource: [$class: "ManuallyEnteredBackrefSource", backref: BUILD_URL],
        statusResultSource: [$class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: MESSAGE, state: STATE]] ]
    ]);
}
