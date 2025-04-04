/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rundeck.controllers

import com.dtolabs.client.utils.Constants
import com.dtolabs.rundeck.app.api.ApiVersions
import com.dtolabs.rundeck.app.api.jobs.info.JobInfo
import com.dtolabs.rundeck.app.api.jobs.info.JobInfoList
import com.dtolabs.rundeck.app.gui.GroupedJobListLinkHandler
import com.dtolabs.rundeck.app.gui.JobListLinkHandlerRegistry
import com.dtolabs.rundeck.app.support.*
import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.authorization.RuleSetValidation
import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.authorization.providers.PolicyCollection
import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.common.IRundeckProject
import com.dtolabs.rundeck.core.config.Features
import com.dtolabs.rundeck.core.extension.ApplicationExtension
import com.dtolabs.rundeck.plugins.scm.ScmPluginException
import com.dtolabs.rundeck.server.AuthContextEvaluatorCacheManager
import grails.converters.JSON
import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.grails.plugins.metricsweb.MetricService
import org.rundeck.app.acl.AppACLContext
import org.rundeck.app.acl.ContextACLManager
import org.rundeck.app.components.RundeckJobDefinitionManager
import org.rundeck.app.components.jobs.JobQuery
import org.rundeck.app.gui.JobListLinkHandler
import org.rundeck.core.auth.AuthConstants
import org.rundeck.core.auth.app.RundeckAccess
import org.rundeck.core.auth.web.RdAuthorizeApplicationType
import org.rundeck.core.auth.web.RdAuthorizeProject
import org.rundeck.core.auth.web.RdAuthorizeSystem
import org.rundeck.util.Sizes
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.web.multipart.MultipartHttpServletRequest
import rundeck.*
import rundeck.codecs.JobsYAMLCodec
import rundeck.services.*
import rundeck.services.feature.FeatureService

import javax.security.auth.Subject
import javax.servlet.http.HttpServletResponse
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

@Transactional
class MenuController extends ControllerBase implements ApplicationContextAware{

    FrameworkService frameworkService
    MenuService menuService
    ExecutionService executionService
    UserService userService
    ScheduledExecutionService scheduledExecutionService
    LogFileStorageService logFileStorageService
    PluginApiService pluginApiService
    MetricService metricService
    JobSchedulesService jobSchedulesService
    ProjectService projectService
    RundeckJobDefinitionManager rundeckJobDefinitionManager
    JobListLinkHandlerRegistry jobListLinkHandlerRegistry
    AuthContextEvaluatorCacheManager authContextEvaluatorCacheManager
    FeatureService featureService

    def configurationService
    ScmService scmService
    def quartzScheduler
    ContextACLManager<AppACLContext> aclFileManagerService
    def ApplicationContext applicationContext
    static allowedMethods = [
            deleteJobfilter                : 'POST',
            storeJobfilter                 : 'POST',
            deleteJobFilterAjax            : 'POST',
            saveJobFilterAjax              : 'POST',
            apiJobDetail                   : 'GET',
            apiResumeIncompleteLogstorage  : 'POST',
            cleanupIncompleteLogStorageAjax:'POST',
            resumeIncompleteLogStorageAjax : 'POST',
            resumeIncompleteLogStorage     : 'POST',
            cleanupIncompleteLogStorage    : 'POST',
            saveProjectAclFile             : 'POST',
            deleteProjectAclFile           : 'POST',
            saveSystemAclFile              : 'POST',
            deleteSystemAclFile            : 'POST',
            listExport                     : 'POST',
            ajaxProjectAclMeta             : 'POST',
            ajaxSystemAclMeta              : 'POST',
    ]

    @CompileStatic
    protected boolean authorizedForEventRead(String project){
        AuthContext authContext =  rundeckAuthContextProcessor.getAuthContextForSubjectAndProject((Subject)session.getProperty('subject'), project)
        return rundeckAuthContextProcessor.authorizeProjectResource(
            authContext,
            AuthConstants.RESOURCE_TYPE_EVENT,
            AuthConstants.ACTION_READ,
            project
        )
    }


    @CompileStatic
    protected boolean apiAuthorizedForEventRead(String project){
        if (authorizedForEventRead(project)) {
            return true
        }
        apiService.renderErrorFormat(
            response,
            [
                status: HttpServletResponse.SC_FORBIDDEN,
                code  : 'api.error.item.unauthorized',
                args  : [AuthConstants.ACTION_READ, 'Events in project', project],
                format: 'json'
            ]
        )
        return false
    }

    def list = {
        def results = index(params)
        render(view:"index",model:results)
    }

    private nowrunning(QueueQuery query) {
        //find currently running executions

        if(params['Clear']){
            query=null
        }
        if(null!=query && !params.find{ it.key.endsWith('Filter')
        }){
            query.recentFilter="1h"
            params.recentFilter="1h"
        }
        if(!query.projFilter && params.project){
            query.projFilter=params.project
        }
        if(null!=query){
            query.configureFilter()
        }

        //find previous executions
        def model = metricService?.withTimer(MenuController.name, actionName+'.queryQueue') {
            executionService.queryQueue(query)
        } ?: executionService.queryQueue(query)
        //        System.err.println("nowrunning: "+model.nowrunning);
        model = executionService.finishQueueQuery(query,params,model)

        //include id of last completed execution for the project
        def eid=executionService.lastExecutionId(query.projFilter)
        model.lastExecId=eid

        User u = userService.findOrCreateUser(session.user)
        Map filterpref=[:]
        if(u){
            filterpref= userService.parseKeyValuePref(u.filterPref)
        }
        model.boxfilters=filterpref
        return model
    }

    def nowrunningAjax(QueueQuery query) {
        if (requireAjax(action: 'index', controller: 'reports', params: params)) {
            return
        }
        if (!params.projFilter && !params.project) {
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                                                           code  : 'api.error.parameter.required', args: ['projFilter or project']])
        }
        def project = params.project ?: params.projFilter
        def allProjects = project == '*'
        if (!allProjects){
            if(!apiService.requireExists(response, frameworkService.existsFrameworkProject(project), ['project', project])) {
                return
            }
            if(!apiAuthorizedForEventRead(project)){
                return
            }
        }
        if (allProjects){
            AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
            def authorized = listProjectsEventReadAuthorized(authContext)

            if (!authorized) {
                return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_UNAUTHORIZED,
                                                               code  : 'api.error.execution.project.notfound', args: [project]])
            }

            query.projFilter = authorized.join(',')
        }else{
            query.projFilter = project
        }
        def results = nowrunning(query)
        //structure dataset for client-side event status processing
        def running= results.nowrunning.collect {
            def map = it.toMap()
            def data = [
                    status: it.executionState,
                    executionHref: createLink(controller: 'execution', action: 'show', absolute: true, id: it.id, params:[project:it.project]),
                    executionId: it.id,
                    duration: (it.dateCompleted?:new Date()).time - it.dateStarted.time
            ]
            if(!it.scheduledExecution){
                data['executionString']=map.workflow.commands[0].exec
            }else{
                data['jobName']=it.scheduledExecution.jobName
                data['jobGroup']=it.scheduledExecution.groupPath
                data['jobId']=it.scheduledExecution.extid
                data['jobPermalink']= createLink(
                        controller: 'scheduledExecution',
                        action: 'show',
                        absolute: true,
                        id: it.scheduledExecution.extid,
                        params:[project:it.scheduledExecution.project]
                )
                if (it.scheduledExecution){
                    def avgDur = it.scheduledExecution.getAverageDuration()
                    if(avgDur > 0) {
                        data['jobAverageDuration'] = avgDur
                    }
                }
                if (it.argString) {
                    data.jobArguments = FrameworkService.parseOptsFromString(it.argString)
                }
            }
            map + data
        }
        render( ([nowrunning:running] + results.subMap(['total','max','offset'])) as JSON)
    }

    def index() {
        /**
         * redirect to configured start page, or default page
         */

        if (!params.project) {
            return redirect(controller: 'menu', action: 'home')
        }
        def startpage = params.page?: grailsApplication.config.rundeck.gui.startpage ?: 'services'
        switch (startpage){
            case 'home':
                return redirect(controller: 'menu', action: 'home')
            case 'projectHome':
                return redirect(controller: 'menu', action: 'projectHome', params: [project: params.project])
            case 'nodes':
                return redirect(controller:'framework',action:'nodes',params:[project:params.project])
            case 'run':
            case 'adhoc':
                return redirect(controller:'framework',action:'adhoc',params:[project:params.project])
            case 'jobs':
                return redirect(controller:'menu',action:'jobs', params: [project: params.project])
            case 'services':
                return redirect(controller:'menu',action:'services', params: [project: params.project])
            case 'jobInstances':
                return redirect(controller:'menu',action:'jobInstances', params: [project: params.project])         
            case 'servicesStatus':
                return redirect(controller:'menu',action:'servicesStatus', params: [project: params.project])
            case 'crSetup':
                return redirect(controller:'menu',action:'crSetup', params: [project: params.project])  
            case 'envUtils':
                return redirect(controller:'menu',action:'envUtils', params: [project: params.project]) 	    
	    case 'createJob':
                return redirect(controller:'scheduledExecution',action: 'create', params: [project: params.project])
            case 'uploadJob':
                return redirect(controller: 'scheduledExecution', action: 'upload', params: [project: params.project])
            case 'configure':
                return redirect(controller: 'framework', action: 'editProject', params: [project: params.project])
            case 'history':
            case 'activity':
            case 'events':
                return redirect(controller:'reports',action:'index', params: [project: params.project])
            case 'reports':
                return redirect(controller:'menu',action:'reports', params: [project: params.project])
        }
        return redirect(controller:'framework',action:'nodes', params: [project: params.project])
    }

    def clearJobsFilter = { ScheduledExecutionQuery query ->
        return redirect(action: 'jobs', params: [project: params.project])
    }
    def services(ScheduledExecutionQuery query ) {
    }
    def jobInstances(ScheduledExecutionQuery query ) {
    }   
    def servicesStatus(ScheduledExecutionQuery query ) {
    } 
    def crSetup(ScheduledExecutionQuery query ) {  
    }
    def envUtils(ScheduledExecutionQuery query ) {
    }
    def reports(ScheduledExecutionQuery query ) {  
    }   
    def jobs (ScheduledExecutionQuery query ){

        def User u = userService.findOrCreateUser(session.user)
        if(params.size()<1 && !params.filterName && u ){
            Map filterpref = userService.parseKeyValuePref(u.filterPref)
            if(filterpref['workflows']){
                params.filterName=filterpref['workflows']
            }
        }
        if(!params.project){
            return redirect(controller: 'menu',action: 'home')
        }
        if(params.jobListType != GroupedJobListLinkHandler.NAME) { //setting request param jobListType=grouped forces this view to render
            JobListLinkHandler jobListLinkHandler = jobListLinkHandlerRegistry.getJobListLinkHandlerForProject(params.project)
            if(jobListLinkHandler && GroupedJobListLinkHandler.NAME != jobListLinkHandler.name) {
                return redirect(jobListLinkHandler.generateRedirectMap([project:params.project]))
            }
        }
        def results = jobsFragment(query, JobsScmInfo.MINIMAL)
        results.execQueryParams=query.asExecQueryParams()
        results.reportQueryParams=query.asReportQueryParams()
        if(results.warning){
            request.warn=results.warning
        }

        def framework = frameworkService.getRundeckFramework()
        def rdprojectconfig = framework.projectManager.loadProjectConfig(params.project)
        results.jobExpandLevel = scheduledExecutionService.getJobExpandLevel(rdprojectconfig)
        results.clusterModeEnabled = frameworkService.isClusterModeEnabled()
        results.jobListIds = results.nextScheduled?.collect {ScheduledExecution job->
            job.extid
        }
        results.scheduledJobListIds = results.scheduledJobs?.collect {ScheduledExecution job->
            job.extid
        }
        def jobQueryComponents = applicationContext.getBeansOfType(JobQuery)

        withFormat{
            html {
                results + [jobQueryComponents:jobQueryComponents]
            }
            yaml{
                final def encoded = JobsYAMLCodec.encode(results.nextScheduled as List)
                render(text:encoded,contentType:"text/yaml",encoding:"UTF-8")
            }
            xml{
                response.setHeader(Constants.X_RUNDECK_RESULT_HEADER,"Jobs found: ${results.nextScheduled?.size()}")
                def writer = new StringWriter()
                rundeckJobDefinitionManager.exportAs('xml',results.nextScheduled, writer)
                writer.flush()
                render(text:writer.toString(),contentType:"text/xml",encoding:"UTF-8")
            }
        }
    }
    /**
     *
     * @param query
     * @return
     */
    def jobsAjax(ScheduledExecutionQuery query){
        if (requireAjax(action: 'jobs', controller: 'menu', params: params)) {
            return
        }
        if(!params.project){
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                                                        code: 'api.error.parameter.required', args: ['project']])
        }
        query.projFilter = params.project
        //test valid project

        if (!apiService.requireExists(
            response,
            frameworkService.existsFrameworkProject(params.project),
            ['project', params.project]
        )) {
            return
        }
        if(query.hasErrors()){
            return apiService.renderErrorFormat(response,
                                                [
                                                        status: HttpServletResponse.SC_BAD_REQUEST,
                                                        code: "api.error.parameter.error",
                                                        args: [query.errors.allErrors.collect { message(error: it) }.join("; ")]
                                                ])
        }
        //don't load scm status for api response
        def results = jobsFragment(query,JobsScmInfo.NONE)
        def clusterModeEnabled = frameworkService.isClusterModeEnabled()
        def serverNodeUUID = frameworkService.serverUUID

        //future scheduled executions forecast
        def futureDate = null
        if (query.daysAhead && query.daysAhead >= 0) {
            futureDate = new Date() + query.daysAhead
        } else if (params.future && Sizes.validTimeDuration(params.future)) {
            def period = Sizes.parseTimeDuration(params.future, TimeUnit.MILLISECONDS)
            futureDate = new Date(System.currentTimeMillis() + period)
        }
        def maxFutures = null
        if (params.maxFutures) {
            maxFutures = params.int('maxFutures')
            if (maxFutures <= 0) {
                maxFutures = null
            }
        }
        def data = new JobInfoList(
                results.nextScheduled.collect { ScheduledExecution se ->
                    Map data = [:]
                    if (clusterModeEnabled) {
                        data = [
                                serverNodeUUID: se.serverNodeUUID,
                                serverOwner   : se.serverNodeUUID == serverNodeUUID
                        ]
                    }

                    if(results.nextExecutions?.get(se.id) || results.nextOneTimeScheduledExecutions?.get(se.id)){
                        data.nextScheduledExecution=results.nextExecutions?.get(se.id)
                        data.nextOneTimeScheduledExecutions=results.nextOneTimeScheduledExecutions?.get(se.id)

                        if(!data.nextScheduledExecution ||
                                (data.nextScheduledExecution &&
                                        data.nextOneTimeScheduledExecutions &&
                                        data.nextScheduledExecution > data.nextOneTimeScheduledExecutions)){
                            data.nextScheduledExecution = data.nextOneTimeScheduledExecutions
                        }

                        if (futureDate) {
                            data.futureScheduledExecutions = []
                            if(data.nextOneTimeScheduledExecutions){
                                data.futureScheduledExecutions += data.nextOneTimeScheduledExecutions
                            }
                            data.futureScheduledExecutions += scheduledExecutionService.nextExecutions(se,futureDate)

                            if (maxFutures
                                && data.futureScheduledExecutions
                                && data.futureScheduledExecutions.size() > maxFutures) {
                                data.futureScheduledExecutions = data.futureScheduledExecutions[0..<maxFutures]
                            }
                        }
                    }
                    if (se.getAverageDuration() > 0) {
                        data.averageDuration = se.getAverageDuration()
                    }
                    JobInfo.from(
                            se,
                            apiService.apiHrefForJob(se),
                            apiService.guiHrefForJob(se),
                            data
                    )
                }
        )
        respond(
                data,
                [formats: [ 'json']]
        )
    }
    static enum JobsScmInfo{
        NONE,
        MINIMAL
    }
    @PackageScope
    def jobsFragment(ScheduledExecutionQuery query, JobsScmInfo scmFlags) {
        long start=System.currentTimeMillis()
        UserAndRolesAuthContext authContext
        def usedFilter=null

        if(params.filterName){
            //load a named filter and create a query from it
            def User u = userService.findOrCreateUser(session.user)
            if(u){
                ScheduledExecutionFilter filter = ScheduledExecutionFilter.findByNameAndUser(params.filterName,u)
                if(filter){
                    def query2 = filter.createQuery()
                    query2.setPagination(query)
                    query=query2
                    def props=query.properties
                    params.putAll(props)
                    usedFilter=params.filterName
                }
            }
        }
        if(params['Clear']){
            query=new ScheduledExecutionQuery()
            usedFilter=null
        }
        if(query && !query.projFilter && params.project) {
            query.projFilter = params.project
        }
        if(query && query.projFilter){
            authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, params.project)
        } else {
            authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        }
        def results=listWorkflows(query,authContext,session.user)

        if (scmFlags == JobsScmInfo.MINIMAL) {
            if (rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                authContext,
                rundeckAuthContextProcessor.authResourceForProject(params.project),
                [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN, AuthConstants.ACTION_EXPORT, AuthConstants.ACTION_IMPORT,
                 AuthConstants.ACTION_SCM_IMPORT, AuthConstants.ACTION_SCM_EXPORT]
            )) {
                def ePluginConfig = scmService.loadScmConfig(params.project, 'export')
                def iPluginConfig = scmService.loadScmConfig(params.project, 'import')
                def eEnabled = ePluginConfig?.enabled && scmService.projectHasConfiguredPlugin('export', params.project)
                def iEnabled = iPluginConfig?.enabled && scmService.projectHasConfiguredPlugin('import', params.project)

                def eConfiguredPlugin = null
                def iConfiguredPlugin = null
                if (ePluginConfig?.type) {
                    eConfiguredPlugin = scmService.getPluginDescriptor('export', ePluginConfig.type)
                }
                if (iPluginConfig?.type) {
                    iConfiguredPlugin = scmService.getPluginDescriptor('import', iPluginConfig.type)
                }
                results.hasConfiguredScmPlugins = (eConfiguredPlugin || iConfiguredPlugin)
                results.hasConfiguredScmPluginsEnabled = (eEnabled || iEnabled)
            }
        }


        if(usedFilter){
            results.filterName=usedFilter
            results.paginateParams['filterName']=usedFilter
        }
        results.params=params

        def remoteClusterNodeUUID=null
        if (frameworkService.isClusterModeEnabled()) {
            results.serverClusterNodeUUID = frameworkService.getServerUUID()
        }
        log.debug("jobsFragment(tot): "+(System.currentTimeMillis()-start));
        return results
    }
    /**
     * Presents the jobs tree and can pass the jobsjscallback parameter
     * to the be a javascript callback for clicked jobs, instead of normal behavior.
     */
    def jobsPicker(ScheduledExecutionQuery query) {

        AuthContext authContext
        def usedFilter=null
        if(!query){
            query = new ScheduledExecutionQuery()
        }
        if(query && !query.projFilter && params.project) {
            query.projFilter = params.project
            authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, params.project)
        } else if(query && query.projFilter){
            authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, query.projFilter)
        } else {
            authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        }
        def results=listWorkflows(query,authContext,session.user)
        if(usedFilter){
            results.filterName=usedFilter
            results.paginateParams['filterName']=usedFilter
        }
        results.params=params
        if(params.jobsjscallback){
            results.jobsjscallback=params.jobsjscallback
        }
        results.showemptymessage=true
        return results + [runAuthRequired:params.runAuthRequired]
    }

    public def jobsSearchJson(ScheduledExecutionQuery query) {
        AuthContext authContext
        def usedFilter = null
        if (!query) {
            query = new ScheduledExecutionQuery()
        }
        if (query && !query.projFilter && params.project) {
            query.projFilter = params.project
            authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, params.project)
        } else if (query && query.projFilter) {
            authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, query.projFilter)
        } else {
            authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        }
        def results = listWorkflows(query, authContext, session.user)
        def runRequired = params.runAuthRequired
        def jobs = runRequired == 'true' ? results.nextScheduled.findAll { se ->
            results.jobauthorizations[AuthConstants.ACTION_RUN]?.contains(se.id.toString())
        } : results.nextScheduled
        def formatted = jobs.collect {ScheduledExecution job->
            [name: job.jobName, group: job.groupPath, project: job.project, id: job.extid, total: results.total]
        }
        respond(
                [formats: ['json']],
                formatted
        )
    }
    private def listWorkflows(ScheduledExecutionQuery query,AuthContext authContext,String user) {
        long start=System.currentTimeMillis()
        if(null!=query){
            query.configureFilter()
        }
        def qres = scheduledExecutionService.listWorkflows(query, params)
        log.debug("service.listWorkflows: "+(System.currentTimeMillis()-start));
        long rest=System.currentTimeMillis()
        def schedlist=qres.schedlist
        def total=qres.total
        def filters=qres._filters

        def finishq=scheduledExecutionService.finishquery(query,params,qres)

        def allScheduled = schedlist.findAll { jobSchedulesService.isScheduled(it.uuid) }
        def nextExecutions=scheduledExecutionService.nextExecutionTimes(allScheduled)
        def nextOneTimeScheduledExecutions = query.runJobLaterFilter ? scheduledExecutionService.nextOneTimeScheduledExecutions(schedlist) : null

        def clusterMap=scheduledExecutionService.clusterScheduledJobs(allScheduled)
        log.debug("listWorkflows(nextSched): "+(System.currentTimeMillis()-rest));
        long preeval=System.currentTimeMillis()

        //collect all jobs and authorize the user for the set of available Job actions
        def jobnames=[:]
        Set res = new HashSet()
        schedlist.each{ ScheduledExecution sched->
            if(!jobnames[sched.generateFullName()]){
                jobnames[sched.generateFullName()]=[]
            }
            jobnames[sched.generateFullName()]<<sched.id.toString()
            res.add(rundeckAuthContextProcessor.authResourceForJob(sched))
        }
        // Filter the groups by what the user is authorized to see.

        def decisions = rundeckAuthContextProcessor.authorizeProjectResources(authContext,res, new HashSet([AuthConstants.ACTION_VIEW, AuthConstants.ACTION_READ, AuthConstants.ACTION_DELETE, AuthConstants.ACTION_RUN, AuthConstants.ACTION_UPDATE, AuthConstants.ACTION_KILL]),query.projFilter)
        log.debug("listWorkflows(evaluate): "+(System.currentTimeMillis()-preeval));

        long viewable=System.currentTimeMillis()

        def authCreate = rundeckAuthContextProcessor.authorizeProjectResource(authContext,
                AuthConstants.RESOURCE_TYPE_JOB,
                AuthConstants.ACTION_CREATE, query.projFilter)


        def Map jobauthorizations=[:]

        //produce map: [actionName:[id1,id2,...],actionName2:[...]] for all allowed actions for jobs
        decisions=decisions.findAll { it.authorized}
        decisions=decisions.groupBy { it.action }
        decisions.each{k,v->
            jobauthorizations[k] = new HashSet(v.collect {
                jobnames[ScheduledExecution.generateFullName(it.resource.group,it.resource.name)]
            }.flatten())
        }

        jobauthorizations[AuthConstants.ACTION_CREATE]=authCreate
        def authorizemap=[:]
        def pviewmap=[:]
        def newschedlist=[]
        def unauthcount=0
        def readauthcount=0

        /*
         'group/name' -> [ jobs...]
         */
        def jobgroups=[:]
        schedlist.each{ ScheduledExecution se->
            authorizemap[se.id.toString()]=jobauthorizations[AuthConstants.ACTION_READ]?.contains(se.id.toString())||jobauthorizations[AuthConstants.ACTION_VIEW]?.contains(se.id.toString())
            if(authorizemap[se.id.toString()]){
                newschedlist<<se
                if(!jobgroups[se.groupPath?:'']){
                    jobgroups[se.groupPath?:'']=[se]
                }else{
                    jobgroups[se.groupPath?:'']<<se
                }
            }
            if(!authorizemap[se.id.toString()]){
                log.debug("Unauthorized job: ${se}")
                unauthcount++
            }

        }
        readauthcount= newschedlist.size()

        if(grailsApplication.config.rundeck?.gui?.realJobTree != "false") {
            //Adding group entries for empty hierachies to have a "real" tree
            def missinggroups = [:]
            jobgroups.each { k, v ->
                def splittedgroups = k.split('/')
                splittedgroups.eachWithIndex { item, idx ->
                    def thepath = splittedgroups[0..idx].join('/')
                    if(!jobgroups.containsKey(thepath)) {
                        missinggroups[thepath]=[]
                    }
                }
            }
            //sorting is done in the view
            jobgroups.putAll(missinggroups)
        }

        schedlist=newschedlist
        log.debug("listWorkflows(viewable): "+(System.currentTimeMillis()-viewable));
        long last=System.currentTimeMillis()

        log.debug("listWorkflows(last): "+(System.currentTimeMillis()-last));
        log.debug("listWorkflows(total): "+(System.currentTimeMillis()-start));

        return [
        nextScheduled:schedlist,
        nextExecutions: nextExecutions,
        scheduledJobs: allScheduled,
        nextOneTimeScheduledExecutions: nextOneTimeScheduledExecutions,
                clusterMap: clusterMap,
        jobauthorizations:jobauthorizations,
        authMap:authorizemap,
        jobgroups:jobgroups,
        paginateParams:finishq.paginateParams,
        displayParams:finishq.displayParams,
        total: total,
        max: finishq.max,
        offset:finishq.offset,
        unauthorizedcount:unauthcount,
        totalauthorized: readauthcount,
        ]
    }


    def storeJobfilter(ScheduledExecutionQuery query, StoreFilterCommand storeFilterCommand){
        withForm{
        if (storeFilterCommand.hasErrors()) {
            flash.errors = storeFilterCommand.errors
            params.saveFilter = true
            return redirect(controller: 'menu', action: 'jobs',
                    params: params.subMap(['newFilterName', 'existsFilterName', 'project', 'saveFilter']))
        }

        def User u = userService.findOrCreateUser(session.user)
        def ScheduledExecutionFilter filter
        def boolean saveuser=false
        if(params.newFilterName && !params.existsFilterName){
            filter= ScheduledExecutionFilter.fromQuery(query)
            filter.name=params.newFilterName
            filter.user=u
            if(!filter.validate()){
                flash.errors = filter.errors
                params.saveFilter=true
                def map = params.subMap(params.keySet().findAll { !it.startsWith('_') })
                return redirect(controller:'menu',action:'jobs',params:map)
            }
            u.addToJobfilters(filter)
            saveuser=true
        }else if(params.existsFilterName){
            filter = ScheduledExecutionFilter.findByNameAndUser(params.existsFilterName,u)
            if(filter){
                filter.properties=query.properties
                filter.fix()
            }
        }else if(!params.newFilterName && !params.existsFilterName){
            flash.error="Filter name not specified"
            params.saveFilter=true
            return redirect(controller:'menu',action:'jobs',params:params)
        }
        if(!filter.save(flush:true)){
            flash.errors = filter.errors
            params.saveFilter=true
            return redirect(controller:'menu',action:'jobs',params:params)
        }
        if(saveuser){
            if(!u.save(flush:true)){
                return renderErrorView([beanErrors: filter.errors])
            }
        }
        redirect(controller:'menu',action:'jobs',params:[filterName:filter.name,project:params.project])
        }.invalidToken {
            renderErrorView(g.message(code:'request.error.invalidtoken.message'))
        }
    }


    def deleteJobfilter={
        withForm{
        def User u = userService.findOrCreateUser(session.user)
        def filtername=params.delFilterName
        final def ffilter = ScheduledExecutionFilter.findByNameAndUser(filtername, u)
        if(ffilter){
            ffilter.delete(flush:true)
            flash.message="Filter deleted: ${filtername.encodeAsHTML()}"
        }
        redirect(controller:'menu',action:'jobs',params:[project: params.project])
        }.invalidToken{
            renderErrorView(g.message(code:'request.error.invalidtoken.message'))
        }
    }

    def deleteJobFilterAjax(String project, String filtername) {
        withForm {
            g.refreshFormTokensHeader()
            def User u = userService.findOrCreateUser(session.user)
            final def ffilter = ScheduledExecutionFilter.findByNameAndUser(filtername, u)
            if (ffilter) {
                ffilter.delete(flush: true)
            }
            render(contentType: 'application/json') {
                success true
            }
        }.invalidToken {
            return apiService.renderErrorFormat(
                    response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code  : 'request.error.invalidtoken.message',
            ]
            )
        }
    }

    def saveJobFilterAjax(ScheduledExecutionQueryFilterCommand query) {
        withForm {
            g.refreshFormTokensHeader()
            if (query.hasErrors()) {
                return apiService.renderErrorFormat(
                        response, [
                        status: HttpServletResponse.SC_BAD_REQUEST,
                        code  : 'api.error.invalid.request',
                        args  : [query.errors.allErrors.collect { it.toString() }.join("; ")]
                ]
                )
            }
            def User u = userService.findOrCreateUser(session.user)
            def ScheduledExecutionFilter filter
            def boolean saveuser = false
            if (query.newFilterName && !query.existsFilterName) {
                if (ScheduledExecutionFilter.findByNameAndUser(query.newFilterName, u)) {
                    return apiService.renderErrorFormat(
                            response, [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code  : 'request.error.conflict.already-exists.message',
                            args  : ["Job Filter", query.newFilterName]
                    ]
                    )
                }
                filter = ScheduledExecutionFilter.fromQuery(query)
                filter.name = query.newFilterName
                filter.user = u
                if (!filter.validate()) {
                    return apiService.renderErrorFormat(
                            response, [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code  : 'api.error.invalid.request',
                            args  : [filter.errors.allErrors.collect { it.toString() }.join("; ")]
                    ]
                    )
                }
                u.addToJobfilters(filter)
                saveuser = true
            } else if (query.existsFilterName) {
                filter = ScheduledExecutionFilter.findByNameAndUser(query.existsFilterName, u)
                if (filter) {
                    filter.properties = query.properties
                    filter.fix()
                }
            }
            if (!filter.save(flush: true)) {
                flash.errors = filter.errors
//                params.saveFilter = true
                return apiService.renderErrorFormat(
                        response, [
                        status: HttpServletResponse.SC_BAD_REQUEST,
                        code  : 'api.error.invalid.request',
                        args  : [filter.errors.allErrors.collect { it.toString() }.join("; ")]
                ]
                )
            }
            if (saveuser) {
                if (!u.save(flush: true)) {
                    return renderErrorView([beanErrors: filter.errors])
                }
            }

            render(contentType: 'application/json') {
                success true
                filterName query.newFilterName
            }
        }.invalidToken {

            return apiService.renderErrorFormat(
                    response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code  : 'request.error.invalidtoken.message',
            ]
            )
        }
    }

    def executionMode(){
        def executionModeActive=configurationService.executionModeActive
        authorizingSystem.authorize(
            executionModeActive?
                RundeckAccess.System.OPS_DISABLE_EXECUTION:
                RundeckAccess.System.OPS_ENABLE_EXECUTION
        )
    }
    def storage(){
        boolean showProjects = params.project ? false : true
        [showProjects: showProjects]
    }

    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_EXPORT)
    def projectExport() {
        [projectComponentMap: projectService.getProjectComponents()]
    }

    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_IMPORT)
    def projectImport() {
        [projectComponentMap: projectService.getProjectComponents()]
    }

    @RdAuthorizeProject(RundeckAccess.General.AUTH_APP_DELETE)
    def projectDelete() {

    }

    @RdAuthorizeSystem(RundeckAccess.General.AUTH_OPS_ADMIN)
    public def resumeIncompleteLogStorage(Long id){
        withForm{

            logFileStorageService.resumeIncompleteLogStorageAsync(frameworkService.serverUUID,id)
//            logFileStorageService.resumeCancelledLogStorageAsync(frameworkService.serverUUID)
            flash.message="Resumed log storage requests"
            return redirect(action: 'logStorage', params: [project: params.project])

        }.invalidToken{

            request.error=g.message(code:'request.error.invalidtoken.message')
            renderErrorView([:])
        }
    }
    @RdAuthorizeSystem(RundeckAccess.General.AUTH_OPS_ADMIN)
    public def resumeIncompleteLogStorageAjax(Long id){
        withForm{

            g.refreshFormTokensHeader()

            logFileStorageService.resumeIncompleteLogStorageAsync(frameworkService.serverUUID,id)
//            logFileStorageService.resumeCancelledLogStorageAsync(frameworkService.serverUUID)
            def message="Resumed log storage requests"
            LogFileStorageRequest req=null
            if(id){
                req=LogFileStorageRequest.get(id)
            }
            withFormat{
                ajax{
                    render(contentType: 'application/json'){
                        status 'ok'
                        delegate.message message
                        if(req){
                            contents exportRequestMap(req, true, false, null)
                        }
                    }
                }
            }

        }.invalidToken{

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'request.error.invalidtoken.message',
            ])
        }
    }
    @RdAuthorizeSystem(RundeckAccess.System.AUTH_READ_OR_OPS_ADMIN)
    def logStorage() {

    }

    /**
     * Remove outstanding queued requests
     * @return
     */
    @RdAuthorizeSystem(RundeckAccess.General.AUTH_OPS_ADMIN)
    def haltIncompleteLogStorage(){
        withForm{

            logFileStorageService.haltIncompleteLogStorage(frameworkService.serverUUID)
            flash.message="Unqueued incomplete log storage requests"
            return redirect(action: 'logStorage', params: [project: params.project])
        }.invalidToken{

            request.error=g.message(code:'request.error.invalidtoken.message')
            renderErrorView([:])

        }
    }

    @RdAuthorizeSystem(RundeckAccess.General.AUTH_OPS_ADMIN)
    def cleanupIncompleteLogStorage(Long id){
        withForm{

            def count=logFileStorageService.cleanupIncompleteLogStorage(frameworkService.serverUUID,id)
            flash.message="Removed $count log storage requests"
            return redirect(action: 'logStorage', params: [project: params.project])
        }.invalidToken{

            request.error=g.message(code:'request.error.invalidtoken.message')
            renderErrorView([:])

        }
    }

    @RdAuthorizeSystem(RundeckAccess.General.AUTH_OPS_ADMIN)
    public def cleanupIncompleteLogStorageAjax(Long id){
        withForm{

            g.refreshFormTokensHeader()


            def count=logFileStorageService.cleanupIncompleteLogStorage(frameworkService.serverUUID,id)
            def message="Removed $count log storage requests"
            withFormat{
                ajax{
                    render(contentType: 'application/json'){
                        status 'ok'
                        delegate.message message
                    }
                }
            }

        }.invalidToken{

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'request.error.invalidtoken.message',
            ])
        }
    }

    @RdAuthorizeSystem(RundeckAccess.System.AUTH_READ_OR_OPS_ADMIN)
    def logStorageIncompleteAjax(BaseQuery query){
        if (requireAjax(action: 'logStorage', controller: 'menu', params: params)) {
            return
        }

        def total=logFileStorageService.countIncompleteLogStorageRequests()
        def list = logFileStorageService.listIncompleteRequests(
                frameworkService.serverUUID,
                [max: query.max, offset: query.offset]
        )
        def queuedIncompleteIds=logFileStorageService.getQueuedIncompleteRequestIds()
        def retryIds=logFileStorageService.getQueuedRetryRequestIds()
        def queuedIds=logFileStorageService.getQueuedRequestIds()
        def failedIds=logFileStorageService.getFailedRequestIds()
        withFormat{
            json{
                render(contentType: "application/json") {
                    incompleteRequests {
                        delegate.'total'  total
                        max  params.max ?: 20
                        offset  params.offset ?: 0
                        contents  list.collect { LogFileStorageRequest req ->
                            exportRequestMap(
                                    req,
                                    retryIds.contains(req.id) || queuedIds.contains(req.id) || queuedIncompleteIds.contains(req.id),
                                    failedIds.contains(req.id),
                                    failedIds.contains(req.id) ? logFileStorageService.getFailures(req.id) : null
                            )
                        }
                    }
                }
            }
        }
    }

    private LinkedHashMap<String, Object> exportRequestMap(LogFileStorageRequest req, isQueued, isFailed, messages) {
        [
                id               : req.id,
                executionId      : req.execution.id,
                project          : req.execution.project,
                href             : apiService.apiHrefForExecution(req.execution),
                permalink        : apiService.guiHrefForExecution(req.execution),
                dateCreated      : req.dateCreated,
                completed        : req.completed,
                filetype         : req.filetype,
                localFilesPresent: logFileStorageService.areAllExecutionFilesPresent(req.execution),
                queued           : isQueued,
                failed           : isFailed,
                messages         : messages
        ]
    }

    @RdAuthorizeSystem(RundeckAccess.System.AUTH_READ_OR_OPS_ADMIN)
    def logStorageMissingAjax(BaseQuery query){
        if (requireAjax(action: 'logStorage', controller: 'menu', params: params)) {
            return
        }

        def totalc=logFileStorageService.countExecutionsWithoutStorageRequests(frameworkService.serverUUID)
        def list = logFileStorageService.listExecutionsWithoutStorageRequests(
                frameworkService.serverUUID,
                [max: query.max, offset: query.offset]
        )
        withFormat{
            json{
                render(contentType: "application/json") {
                    missingRequests {
                        total  totalc
                        max  params.max ?: 20
                        offset  params.offset ?: 0
                        contents  list.collect { Execution req ->
                            [
                                    id     : req.id,
                                    project: req.project,
                                    href   : createLink(
                                            action: 'show',
                                            controller: 'execution',
                                            params: [project: req.project, id: req.id]
                                    ),
//                                    summary: executionService.summarizeJob(req.scheduledExecution, req)
                            ]
                        }
                    }
                }
            }
        }
    }
    @RdAuthorizeSystem(RundeckAccess.System.AUTH_READ_OR_OPS_ADMIN)
    def logStorageAjax(){
        if (requireAjax(action: 'logStorage', controller: 'menu', params: params)) {
            return
        }

        def data = logFileStorageService.getStorageStats()
        data.retryDelay=logFileStorageService.getConfiguredStorageRetryDelay()
        return render(contentType: 'application/json', text: data + [enabled: data.pluginName ? true : false] as JSON)
    }

    @RdAuthorizeSystem(value = RundeckAccess.System.AUTH_READ_OR_ANY_ADMIN, description = 'Read System Configuration')
    def systemConfig(){

        if(!grailsApplication.config.dataSource.jndiName &&
                grailsApplication.config.dataSource.driverClassName=='org.h2.Driver'){
            flash.error=message(code: "development.mode.warning")
        }
        [rundeckFramework: frameworkService.rundeckFramework]
    }

    def securityConfig() {
        return redirect(action: 'acls', params: params)
    }

    private Map storeCachedPolicyMeta(String project, String type, String name, Map meta) {
        String key = project ? "proj:$project" : type
        if (null == session.menu_acl_data_cache) {
            session.menu_acl_data_cache = [:]
        }
        if (null == session.menu_acl_data_cache[key]) {
            session.menu_acl_data_cache[key] = [:]
        }
        session.menu_acl_data_cache[key][name] = meta
        meta
    }

    private Map getCachedPolicyMeta(String name, String project, String type, Closure gen = null) {
        String key = project ? "proj:$project" : type

        def value = session.menu_acl_data_cache?.get(key)?.get(name)
        if (!value && gen != null) {
            value = gen()
            if (value != null) {
                storeCachedPolicyMeta(project, type, name, value)
            }
        }
        value
    }

    def projectAcls() {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        if (!params.project) {
            return renderErrorView('Project parameter is required')
        }
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProjectAcl(params.project),
                        [AuthConstants.ACTION_READ, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                AuthConstants.ACTION_READ, 'ACL for Project', params.project
        )) {
            return
        }

        List<Map> projectlist = listProjectAclFiles(params.project)
        [
                assumeValid     : true,
                acllist         : projectlist,
        ]
    }

    @CompileStatic
    protected RuleSetValidation<PolicyCollection> loadProjectPolicyValidation(
        String projectName,
        String ident,
        String fileText = null
    ) {
        if (fileText == null) {
            if (!aclFileManagerService.existsPolicyFile(AppACLContext.project(projectName), ident)) {
                return null
            }
            fileText = aclFileManagerService.getPolicyFileContents(AppACLContext.project(projectName), ident)
        }
        aclFileManagerService.validateYamlPolicy(AppACLContext.project(projectName),ident, fileText)
    }

    private Map policyMetaFromValidation(RuleSetValidation<PolicyCollection> policiesvalidation) {
        def meta = [:]
        if (policiesvalidation?.source?.policies) {
            meta.description = policiesvalidation?.source?.policies?.first()?.description
        }
        if (policiesvalidation?.source?.countPolicies()) {
            meta.count = policiesvalidation?.source?.countPolicies()
            //
            meta.policies = policiesvalidation?.source?.policies?.collect(){
                def by = it.isBy()?'by:':'notBy:'
                if(it.groups?.size()>0){
                    by = by+' group: '+it.groups.join(", ")
                }
                if(it.usernames?.size()>0){
                    by = by+' usernames: '+it.usernames.join(", ")
                }
                if(it.urns?.size()>0){
                    by = by+' urn: '+it.urns.join(", ")
                }

                [
                        description: it.description,
                        by: by
                ]
            }
        }
        meta ?: null
    }

    private List<Map> listProjectAclFiles(String project) {
        def projectlist = aclFileManagerService.listStoredPolicyFiles(AppACLContext.project(project)).sort().collect { fname ->
            [
                    id  : fname,
                    name: AclFile.idToName(fname),
                    valid: true
            ]
        }
        projectlist
    }
    def ajaxProjectAclMeta() {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        if (requireAjax(controller: 'menu', action: 'projectAcls', params: params)) {
            return
        }
        def project = params.project
        if (!project) {
            return renderErrorView('Project parameter is required')
        }
        if (unauthorizedResponse(
            rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                authContext,
                rundeckAuthContextProcessor.authResourceForProjectAcl(project),
                [AuthConstants.ACTION_READ, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
            ),
            AuthConstants.ACTION_READ, 'ACL for Project', project
        )) {
            return
        }

        if ( !(request.JSON) || !request.JSON.files) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return respond([error: [message: g.message(code:'api.error.invalid.request',args:['JSON body must contain files entry'])]], formats: ['json'])
        }
        def list = request.JSON.files ?: []
        def result = list.collect{String fname->
            def validation = loadProjectPolicyValidation(project, fname)
            Map meta = getCachedPolicyMeta(fname, project, null) {
                policyMetaFromValidation(validation)
            }
            [
                    id  : fname,
                    name: AclFile.idToName(fname),
                    meta: (meta ?: [:]),
                    validation: validation ? validation.errors : [(fname): ['Not found']],
                    valid: !!validation?.valid
            ]
        }
        respond((Object) result, formats: ['json'])
    }

    def createProjectAclFile() {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        if (!params.project) {
            return renderErrorView('Project parameter is required')
        }
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProjectAcl(params.project),
                        [AuthConstants.ACTION_CREATE, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                AuthConstants.ACTION_CREATE, 'ACL for Project', params.project
        )) {
            return
        }

        String fileText = ""
        if(params.fileText){
            fileText = params.fileText
        }
        //TODO: templates
        [project: params.project, fileText:fileText]
    }

    def editProjectAclFile(ProjAclFile input) {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        def project = params.project
        if (!project) {
            return renderErrorView('Project parameter is required')
        }
        input.validate()
        if(!input.id) {
            input.errors.rejectValue('id', 'blank',['id'].toArray(),null)
        }
        if (input.hasErrors()) {
            request.errors = input.errors
            return renderErrorView([:])
        }
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProjectAcl(project),
                        [AuthConstants.ACTION_UPDATE, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                AuthConstants.ACTION_UPDATE, 'ACL for Project', project
        )) {
            return
        }

        def resourceExists = aclFileManagerService.existsPolicyFile(AppACLContext.project(project), input.id)

        if (notFoundResponse(resourceExists, 'ACL File in Project: ' + project, input.id)) {
            return
        }

        def fileText = aclFileManagerService.getPolicyFileContents(AppACLContext.project(project), input.id)
        def size=fileText.length()
        def policiesvalidation = loadProjectPolicyValidation(
            project,
            input.id,
            fileText
        )
        [
                fileText  : fileText,
                id        : input.id,
                name      : input.idToName(),
                project   : project,
                size      : size,
                validation: policiesvalidation,
                meta      : getCachedPolicyMeta(input.id, project, null) {
                    policyMetaFromValidation(policiesvalidation)
                }
        ]
    }

    def deleteProjectAclFile(ProjAclFile input) {
        if (params.cancel) {
            return redirect(controller: 'menu', action: 'projectAcls', params: [project: params.project])
        }
        if (!requestHasValidToken()) {
            return
        }
        input.validate()
        if(!input.id) {
            input.errors.rejectValue('id', 'blank',['id'].toArray(),null)
        }
        if (input.hasErrors()) {
            request.errors = input.errors
            return renderErrorView([:])
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        def project = params.project
        if (!project) {
            return renderErrorView('Project parameter is required')
        }
        def requiredAuth = AuthConstants.ACTION_DELETE
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProjectAcl(project),
                        [requiredAuth, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                requiredAuth, 'ACL for Project', project
        )) {
            return
        }
        if (notFoundResponse(frameworkService.existsFrameworkProject(project), 'Project', project)) {
            return
        }

        def resourceExists = aclFileManagerService.existsPolicyFile(AppACLContext.project(project), input.id)

        if (notFoundResponse(resourceExists, 'ACL File in Project: ' + project, input.id)) {
            return
        }
        //store
        try {
            if (aclFileManagerService.deletePolicyFile(AppACLContext.project(project),input.id)) {
                flash.message = input.id + " was deleted"
            } else {
                flash.error = input.id + " was NOT deleted"
            }
        } catch (IOException e) {
            log.error("Error deleting project acl: $input.id: $e.message", e)
            request.error = e.message
        }
        return redirect(controller: 'menu', action: 'projectAcls', params: [project: project])
    }
    /**
     * Endpoint for save/upload
     * @param input
     * @return
     */
    def saveProjectAclFile(SaveProjAclFile input) {
        if (params.cancel) {
            return redirect(controller: 'menu', action: 'projectAcls', params: [project: params.project])
        }
        if (!requestHasValidToken()) {
            return
        }
        def renderInvalid = { Map model = [:] ->
            if(input.upload){
                model.acllist = listProjectAclFiles(params.project)
            }
            render(
                    view: input.upload ? 'projectAcls' : input.create ? 'createProjectAclFile' : 'editProjectAclFile',
                    model:
                    [
                            input   : input,
                            fileText: input.fileText,
                            id      : input.id,
                            name    : input.name,
                            project : params.project,
                            size    : input.fileText?.length(),
                    ] + model
            )
        }
        if (input.upload) {
            if(request instanceof MultipartHttpServletRequest){
                def file = request.getFile('uploadFile')
                input.fileText = new String(file.bytes)
            }
            else {
                response.status = HttpServletResponse.SC_BAD_REQUEST
                return renderErrorView("Expected multipart file upload request")
            }
        }
        input.validate()
        if(!input.id && !input.name) {
            input.errors.rejectValue('id', 'blank',['id'].toArray(),null)
        }
        if (input.hasErrors()) {
            request.errors = input.errors
            return renderInvalid()
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        def project = params.project
        if (!project) {
            return renderErrorView('Project parameter is required')
        }

        def resourceExists = aclFileManagerService.
            existsPolicyFile(AppACLContext.project(project), input.createId())
        def requiredAuth = (input.upload && !resourceExists || input.create) ? AuthConstants.ACTION_CREATE :
                AuthConstants.ACTION_UPDATE
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProjectAcl(project),
                        [requiredAuth, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                requiredAuth, 'ACL for Project', project
        )) {
            return
        }
        if ((input.create || input.upload && !input.overwrite) && resourceExists) {
            input.errors.rejectValue(
                    'name',
                    'policy.create.conflict',
                    [input.createName()].toArray(),
                    "ACL Policy already exists: {0}"
            )
            response.status = HttpServletResponse.SC_CONFLICT
            request.errors = input.errors
            return renderInvalid()
        }
        if (!input.create && !input.upload &&
                notFoundResponse(resourceExists, 'ACL Policy in Project: ' + project, input.createName())) {
            return
        }
        def error = false
        //validate

        String fileText = input.fileText
        def validation = aclFileManagerService.validateYamlPolicy(
                AppACLContext.project(project),
                input.upload ? 'uploaded-file' : input.createId(),
                fileText
        )
        if (!validation.valid) {
            request.error = "Validation failed"
            return renderInvalid(validation: validation)
        }
        storeCachedPolicyMeta(project, null, input.createId(), policyMetaFromValidation(validation))
        //store
        try {
            def size = aclFileManagerService.
                storePolicyFileContents(AppACLContext.project(project), input.createId(), fileText)
            flash.storedFile = input.createId()
            flash.storedSize = size
        } catch (IOException e) {
            log.error("Error storing project acl: ${input.createId()}: $e.message", e)
            request.error = e.message
            error = true
        }
        return redirect(controller: 'menu', action: 'projectAcls', params: [project: project])
    }

    @RdAuthorizeApplicationType(
        type = AuthConstants.TYPE_SYSTEM_ACL,
        access = RundeckAccess.General.AUTH_APP_READ
    )
    def acls() {
        systemAclsModel()
    }

    private validateSystemPolicyFSFile(String fname) {
        def fwkConfigDir = frameworkService.getFrameworkConfigDir()
        def file = new File(fwkConfigDir, fname)
        aclFileManagerService.forContext(AppACLContext.system()).validator.validateYamlPolicy(fname, file)
    }

    private Map systemAclsModel() {
        def fwkConfigDir = frameworkService.getFrameworkConfigDir()
        def fslist = fwkConfigDir.listFiles().grep { it.name =~ /\.aclpolicy$/ }.sort().collect { file ->
            def validation = validateSystemPolicyFSFile(file.name)
            [
                    id        : file.name,
                    name      : AclFile.idToName(file.name),
                    meta      : getCachedPolicyMeta(file.name, null, 'fs') {
                        policyMetaFromValidation(validation)
                    },
                    validation: validation?.errors,
                    valid     : validation?.valid
            ]
        }
        def stored = aclFileManagerService.listStoredPolicyFiles(AppACLContext.system()).sort().collect { fname ->
            [
                    id   : fname,
                    name : AclFile.idToName(fname),
                    valid: true
            ]
        }

        [
                fwkConfigDir : fwkConfigDir,
                aclFileList  : fslist,
                aclStoredList: stored,
                clusterMode  : isClusterModeAclsLocalFileEditDisabled()
        ]
    }
    def ajaxSystemAclMeta(){
        if(requireAjax(controller: 'menu',action:'acls')){
            return
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
            rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                authContext,
                AuthConstants.RESOURCE_TYPE_SYSTEM_ACL,
                [AuthConstants.ACTION_READ, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
            ),
            AuthConstants.ACTION_READ, 'System configuration')) {
            return
        }
        //list of acl files

        if ( !(request.JSON) || !request.JSON.files) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return respond([error: [message: g.message(code:'api.error.invalid.request',args:['JSON body must contain files entry'])]], formats: ['json'])
        }
        def list = request.JSON.files ?: []
        def result = list.collect{String fname->
            def validation = aclFileManagerService.validatePolicyFile(AppACLContext.system(),fname)
            [
                id   : fname,
                name : AclFile.idToName(fname),
                meta : getCachedPolicyMeta(fname, null, 'storage') {
                    policyMetaFromValidation(validation)
                }?:[:],
                validation: validation ? validation.errors : [(fname): ['Not found']],
                valid: !!validation?.valid
            ]
        }
        respond((Object) result, formats: ['json'])
    }

    protected boolean isClusterModeAclsLocalFileEditDisabled() {
        frameworkService.isClusterModeEnabled() &&
                configurationService.getBoolean('clusterMode.acls.localfiles.modify.disabled', true)
    }

    def createSystemAclFile() {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        if (!params.fileType || !(params.fileType in ['fs', 'storage'])) {
            return renderErrorView('fileType parameter is required, must be one of: fs, storage')
        }

        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        AuthConstants.RESOURCE_TYPE_SYSTEM_ACL,
                        [AuthConstants.ACTION_CREATE, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                AuthConstants.ACTION_CREATE, 'System ACLs'
        )) {
            return
        }

        if (params.fileType == 'fs' && isClusterModeAclsLocalFileEditDisabled()) {
            return renderErrorView(message(code:"clusterMode.acls.localfiles.modify.disabled.warning.message"))
        }

        String fileText = ""
        if(params.fileText){
            fileText = params.fileText
        }
        //TODO: templates
        [fileType: params.fileType, fileText:fileText]
    }

    def editSystemAclFile(SysAclFile input) {
        input.validate()
        if(!input.id) {
            input.errors.rejectValue('id', 'blank',['id'].toArray(),null)
        }
        if (input.hasErrors()) {
            request.errors = input.errors
            return renderErrorView([:])
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        AuthConstants.RESOURCE_TYPE_SYSTEM_ACL,
                        [AuthConstants.ACTION_UPDATE, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                AuthConstants.ACTION_UPDATE, 'System ACLs'
        )) {
            return
        }

        if (input.fileType == 'fs' && isClusterModeAclsLocalFileEditDisabled()) {
            return renderErrorView(message(code:"clusterMode.acls.localfiles.modify.disabled.warning.message"))
        }
        def fileText
        def exists = false
        def size
        if (input.fileType == 'fs') {
            //look on filesys
            def fwkConfigDir = frameworkService.getFrameworkConfigDir()
            def file = new File(fwkConfigDir, input.id)
            exists = frameworkService.existsFrameworkConfigFile(input.id)
            if (exists) {
                fileText = frameworkService.readFrameworkConfigFile(input.id)
                size = fileText.length()
            }
        } else if (input.fileType == 'storage') {
            //look in storage
            exists = aclFileManagerService.existsPolicyFile(AppACLContext.system(),input.id)
            if (exists) {
                fileText = aclFileManagerService.getPolicyFileContents(AppACLContext.system(),input.id)
                size = fileText.length()
            }
        }
        if (notFoundResponse(exists, "System ACL Policy File", input.id)) {
            return
        }

        [
                fileText: fileText,
                id      : input.id,
                name    : input.idToName(),
                fileType: input.fileType,
                size    : size
        ]
    }

    def saveSystemAclFile(SaveSysAclFile input) {
        if (params.cancel) {
            return redirect(controller: 'menu', action: 'acls')
        }
        if (!requestHasValidToken()) {
            return
        }
        def renderInvalid = { Map model = [:] ->
            if (input.upload) {
                model += systemAclsModel()
            }
            render(view: input.upload ? 'acls' : input.create ? 'createSystemAclFile' : 'editSystemAclFile', model:
                    [
                            input   : input,
                            fileText: input.fileText,
                            id      : input.id,
                            name    : input.name ?: input.idToName(),
                            fileType: input.fileType,
                            size    : input.fileText?.length(),
                    ] + model
            )
        }

        if (input.upload) {
            if(request instanceof MultipartHttpServletRequest){
                def file = request.getFile('uploadFile')
                input.fileText = new String(file.bytes)
            }
            else {
                response.status = HttpServletResponse.SC_BAD_REQUEST
                return renderErrorView("Expected multipart file upload request")
            }

        }
        input.validate()
        if (!input.id && !input.name) {
            input.errors.rejectValue('id', 'blank', ['id'].toArray(), null)
        }
        if (input.hasErrors()) {
            request.errors = input.errors
            return renderInvalid()
        }
        if (input.fileType == 'fs' && isClusterModeAclsLocalFileEditDisabled()) {
            return renderErrorView(message(code:"clusterMode.acls.localfiles.modify.disabled.warning.message"))
        }
        def exists = false
        if (input.fileType == 'fs') {
            //look on filesys
            exists = frameworkService.existsFrameworkConfigFile(input.createId())
        } else if (input.fileType == 'storage') {
            //look in storage
            exists = aclFileManagerService.existsPolicyFile(AppACLContext.system(),input.createId())
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        def requiredAuth = (input.upload && !exists || input.create) ? AuthConstants.ACTION_CREATE :
                AuthConstants.ACTION_UPDATE
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        AuthConstants.RESOURCE_TYPE_SYSTEM_ACL,
                        [requiredAuth, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                requiredAuth, 'System ACLs'
        )) {
            return
        }
        if ((input.create || input.upload && !input.overwrite) && exists) {
            input.errors.rejectValue(
                    'name',
                    'policy.create.conflict',
                    [input.createName()].toArray(),
                    "Policy Name already exists: {0}"
            )
        } else if (!input.create && !input.upload &&
                notFoundResponse(exists, 'System ACL Policy', input.createName())) {
            return
        }
        if (input.errors.hasErrors()) {
            return renderInvalid()
        }

        String fileText = input.fileText
        def validation = aclFileManagerService.validateYamlPolicy(AppACLContext.system(), input.upload ? 'uploaded-file' : input.id, fileText)
        if (!validation.valid) {
            request.error = "Validation failed"
            return renderInvalid(validation: validation)
        }
        storeCachedPolicyMeta(null, input.fileType, input.createId(), policyMetaFromValidation(validation))
        //store
        if (input.fileType == 'fs') {
            //store on filesys
            try {
                flash.storedSize = frameworkService.writeFrameworkConfigFile(input.createId(), fileText)
                flash.storedFile = input.createName()
                flash.storedType = input.fileType
            } catch (IOException exc) {
                flash.error = "Failed saving file: $exc"
                return renderInvalid()
            }

        } else if (input.fileType == 'storage') {
            //store in storage
            flash.storedSize = aclFileManagerService.storePolicyFileContents(AppACLContext.system(),input.createId(), fileText)
            flash.storedFile = input.createName()
            flash.storedType = input.fileType
        }

        return redirect(controller: 'menu', action: 'acls')
    }

    def deleteSystemAclFile(SysAclFile input) {
        if (params.cancel) {
            return redirect(controller: 'menu', action: 'acls')
        }
        if (!requestHasValidToken()) {
            return
        }

        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        def requiredAuth = AuthConstants.ACTION_DELETE
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        AuthConstants.RESOURCE_TYPE_SYSTEM_ACL,
                        [requiredAuth, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                ),
                requiredAuth, 'System ACLs'
        )) {
            return
        }

        input.validate()
        if(!input.id) {
            input.errors.rejectValue('id', 'blank',['id'].toArray(),null)
        }
        if (input.hasErrors()) {
            request.errors = input.errors
            return renderErrorView([:])
        }

        if (input.fileType == 'fs' && isClusterModeAclsLocalFileEditDisabled()) {
            return renderErrorView(message(code:"clusterMode.acls.localfiles.modify.disabled.warning.message"))
        }
        def exists = false
        if (input.fileType == 'fs') {
            //look on filesys
            exists = frameworkService.existsFrameworkConfigFile(input.id)
        } else if (input.fileType == 'storage') {
            //look in storage
            exists = aclFileManagerService.existsPolicyFile(AppACLContext.system(),input.id)
        }

        if (notFoundResponse(exists, 'System ACL Policy', input.id)) {
            return
        }
        if (input.fileType == 'fs') {
            //store on filesys
            boolean deleted=frameworkService.deleteFrameworkConfigFile(input.id)
            flash.message = "Policy was deleted: " + input.id
        } else if (input.fileType == 'storage') {
            //store in storage
            if (aclFileManagerService.deletePolicyFile(AppACLContext.system(),input.id)) {
                flash.message = "Policy was deleted: " + input.id
            } else {
                flash.error = "Policy was NOT deleted: " + input.id
            }
        }
        return redirect(controller: 'menu', action: 'acls')
    }

    @RdAuthorizeSystem(RundeckAccess.System.AUTH_READ_OR_OPS_ADMIN)
    def systemInfo (){
        if(!grailsApplication.config.dataSource.jndiName &&
                grailsApplication.config.dataSource.driverClassName=='org.h2.Driver'){
            flash.error=message(code: "development.mode.warning")
        }

        Date nowDate = new Date();
        String nodeName = servletContext.getAttribute("FRAMEWORK_NODE")
        String appVersion = grailsApplication.metadata['info.app.version']
        double load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
        int processorsCount = ManagementFactory.getOperatingSystemMXBean().availableProcessors
        String osName = ManagementFactory.getOperatingSystemMXBean().name
        String osVersion = ManagementFactory.getOperatingSystemMXBean().version
        String osArch = ManagementFactory.getOperatingSystemMXBean().arch
        String javaVendor = System.properties['java.vendor']
        String javaVersion = System.properties['java.version']
        String vmName = ManagementFactory.getRuntimeMXBean().vmName
        String vmVendor = ManagementFactory.getRuntimeMXBean().vmVendor
        String vmVersion = ManagementFactory.getRuntimeMXBean().vmVersion
        long durationTime = ManagementFactory.getRuntimeMXBean().uptime
        Date startupDate = new Date(nowDate.getTime() - durationTime)
        int threadActiveCount = Thread.activeCount()
        def build = grailsApplication.metadata['build.ident']
        def buildGit = grailsApplication.metadata['build.core.git.description']
        def base = servletContext.getAttribute("RDECK_BASE")
        boolean executionModeActive=configurationService.executionModeActive
        String apiVersion = ApiVersions.API_CURRENT_VERSION

        def memmax = Runtime.getRuntime().maxMemory()
        def memfree = Runtime.getRuntime().freeMemory()
        def memtotal = Runtime.getRuntime().totalMemory()
        def schedulerRunningCount = quartzScheduler.getCurrentlyExecutingJobs().size()
        def threadPoolSize = quartzScheduler.getMetaData().threadPoolSize
        def info = [
            nowDate: nowDate,
            nodeName: nodeName,
            appVersion: appVersion,
            apiVersion: apiVersion,
            load: load,
            processorsCount: processorsCount,
            osName: osName,
            osVersion: osVersion,
            osArch: osArch,
            javaVendor: javaVendor,
            javaVersion: javaVersion,
            vmName: vmName,
            vmVendor: vmVendor,
            vmVersion: vmVersion,
            durationTime: durationTime,
            startupDate: startupDate,
            threadActiveCount: threadActiveCount,
            build: build,
            buildGit: buildGit,
            base: base,
            memmax: memmax,
            memfree: memfree,
            memtotal: memtotal,
            schedulerRunningCount: schedulerRunningCount,
            threadPoolSize: threadPoolSize,
            executionModeActive:executionModeActive
        ]
        def schedulerThreadRatio=info.threadPoolSize>0?(info.schedulerRunningCount/info.threadPoolSize):0
        def serverUUID=frameworkService.getServerUUID()
        if(serverUUID){
            info['serverUUID']=serverUUID
        }

        def extMeta = []
        def loader = ServiceLoader.load(ApplicationExtension)
        loader.each {
            extMeta<<[(it.name):it.infoMetadata]
        }
        return [
                schedulerThreadRatio:schedulerThreadRatio,
                schedulerRunningCount:info.schedulerRunningCount,
                threadPoolSize:info.threadPoolSize,
                systemInfo: [

            [
                "stats: uptime": [
                    duration: info.durationTime,
                    'duration.unit': 'ms',
                    datetime: g.w3cDateValue(date: info.startupDate)
                ]],

            ["stats: cpu": [

                loadAverage: info.load,
                'loadAverage.unit': '%',
                processors: info.processorsCount
            ]],
            ["stats: memory":
            [
                'max.unit': 'byte',
                'free.unit': 'byte',
                'total.unit': 'byte',
                max: info.memmax,
                free: info.memfree,
                total: info.memtotal,
                used: info.memtotal-info.memfree,
                'used.unit': 'byte',
                heapusage: (info.memtotal-info.memfree)/info.memtotal,
                'heapusage.unit': 'ratio',
                'heapusage.info': 'Ratio of Used to Free memory within the Heap (used/total)',
                allocated: (info.memtotal)/info.memmax,
                'allocated.unit': 'ratio',
                'allocated.info': 'Ratio of system memory allocated to maximum allowed (total/max)',
            ]],
            ["stats: scheduler":
            [
                    running: info.schedulerRunningCount,
                    threadPoolSize:info.threadPoolSize,
                    ratio: (schedulerThreadRatio),
                    'ratio.unit':'ratio',
                    'ratio.info':'Ratio of used threads to Quartz scheduler thread pool size'
            ]
            ],
            ["stats: threads":
            [active: info.threadActiveCount]
            ],
            [timestamp: [
//                epoch: info.nowDate.getTime(), 'epoch.unit': 'ms',
                datetime: g.w3cDateValue(date: info.nowDate)
            ]],

            [rundeck:
            [version: info.appVersion,
                build: info.build,
                buildGit: info.buildGit,
                node: info.nodeName,
                base: info.base,
                serverUUID: info.serverUUID,
                apiversion: info.apiVersion,
            ]],
            [
                    executions:[
                            active: info.executionModeActive,
                            executionMode:info.executionModeActive?'ACTIVE':'PASSIVE',
                            'executionMode.status':info.executionModeActive?'success':'warning'
                    ]
            ],
            [os:
            [arch: info.osArch,
                name: info.osName,
                version: info.osVersion,
            ]
            ],
            [jvm:
            [
                vendor: info.javaVendor,
                version: info.javaVersion,
                name: info.vmName,
                implementationVersion: info.vmVersion
            ],
            ],
        ] + extMeta]
    }

    def licenses(){

    }

    def plugins(){
        pluginApiService.listPluginsDetailed()
    }

    def home() {
        //first-run html info
        def isFirstRun=false

        if (configurationService.getBoolean('startup.alwaysFirstRun', false)) {
            isFirstRun=true
        }else{
            if(configurationService.getBoolean("startup.detectFirstRun",true) &&
                    frameworkService.rundeckFramework.hasProperty('framework.var.dir')) {
                def vardir = frameworkService.rundeckFramework.getProperty('framework.var.dir')
                String buildIdent = grailsApplication.metadata.getProperty('build.ident', String)
                def vers = buildIdent.replaceAll('\\s+\\(.+\\)$','')
                def file = new File(vardir, ".first-run-${vers}")
                if(!file.exists()){
                    isFirstRun=true
                    file.withWriter("UTF-8"){out->
                        out.write('#'+(new Date().toString()))
                    }
                }
            }
        }

        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        def fprojects=null
        if(session.frameworkProjects || featureService.featurePresent(Features.SIDEBAR_PROJECT_LISTING)) {
            long start = System.currentTimeMillis()
            fprojects = frameworkService.refreshSessionProjects(authContext, session, params.refresh=='true')
            log.debug("frameworkService.projectNames(context)... ${System.currentTimeMillis() - start}")
        }
        def statsLoaded = false
        def stats=[:]
        if(fprojects && session.summaryProjectStats){
            stats=cachedSummaryProjectStats(fprojects)
            statsLoaded=true
        }
        //isFirstRun = true //as
        render(view: 'home', model: [
                isFirstRun:isFirstRun,
                projectNames: fprojects,
                statsLoaded: statsLoaded,
                execCount:stats.execCount,
                totalFailedCount:stats.totalFailedCount,
                recentUsers:stats.recentUsers,
                recentProjects:stats.recentProjects
        ])
    }

    def projectHome() {
        if (!params.project) {
            return redirect(controller: 'menu', action: 'home')
        }
        [project: params.project]
    }

    def welcome(){
    }

    private def cachedSummaryProjectStats(final List projectNames) {
        long now = System.currentTimeMillis()
        if (null == session.summaryProjectStats ||
                session.summaryProjectStats_expire < now ||
                session.summaryProjectStatsSize != projectNames.size()) {
            if (configurationService.getBoolean("menuController.projectStats.queryAlt", false)) {
                session.summaryProjectStats = metricService?.withTimer(
                        MenuController.name,
                        'loadSummaryProjectStatsAlt'
                ) {
                    loadSummaryProjectStatsAlt(projectNames)
                } ?: loadSummaryProjectStatsAlt(projectNames)
            } else {
                session.summaryProjectStats = metricService?.withTimer(
                        MenuController.name,
                        'loadSummaryProjectStatsOrig'
                ) {
                    loadSummaryProjectStatsOrig(projectNames)
                } ?: loadSummaryProjectStatsOrig(projectNames)
            }
            session.summaryProjectStatsSize = projectNames.size()
            session.summaryProjectStats_expire = now + (60 * 1000)
        }
        return session.summaryProjectStats
    }

    private def loadSummaryProjectStatsAlt(final List projectNames) {
        long start = System.currentTimeMillis()
        Calendar n = GregorianCalendar.getInstance()
        n.add(Calendar.DAY_OF_YEAR, -1)
        Date lastday = n.getTime()
        def summary = [:]
        def projects = new TreeSet()
        projectNames.each { project ->
            summary[project] = [name: project, execCount: 0, failedCount: 0, userSummary: [], userCount: 0]
        }
        long proj2 = System.currentTimeMillis()
        def executionResults = projectNames?Execution.createCriteria().list {
            gt('dateStarted', lastday)
            projections {
                property('project')
                property('status')
                property('user')
            }
        }:[]
        executionResults = executionResults.findAll{projectNames.contains(it[0])}
        proj2 = System.currentTimeMillis() - proj2
        long proj3 = System.currentTimeMillis()
        def projexecs = executionResults.groupBy { it[0] }
        proj3 = System.currentTimeMillis() - proj3

        def execCount = executionResults.size()
        def totalFailedCount = 0
        def users = new HashSet<String>()
        projexecs.each { name, val ->
            if (summary[name]) {
                summary[name.toString()].execCount = val.size()
                projects << name
                def failedlist = val.findAll { it[1] in ['false', 'failed'] }

                summary[name.toString()].failedCount = failedlist.size()
                totalFailedCount += failedlist.size()
                def projusers = new HashSet<String>()
                projusers.addAll(val.collect { it[2] })

                summary[name.toString()].userSummary.addAll(projusers)
                summary[name.toString()].userCount = projusers.size()
                users.addAll(projusers)
            }
        }

        log.error(
                "loadSummaryProjectStats... ${System.currentTimeMillis() - start}, projexeccount ${proj2}, " +
                        "projuserdistinct ${proj3}"
        )
        [summary: summary, recentUsers: users, recentProjects: projects, execCount: execCount, totalFailedCount: totalFailedCount]
    }
    /**
     *
     * @param projectNames
     * @return
     */
    private def loadSummaryProjectStatsOrig(final List projectNames) {
        long start=System.currentTimeMillis()
        Calendar n = GregorianCalendar.getInstance()
        n.add(Calendar.DAY_OF_YEAR, -1)
        Date today = n.getTime()
        def summary=[:]
        def projects = []
        projectNames.each{project->
            summary[project]=[name: project, execCount: 0, failedCount: 0,userSummary: [], userCount: 0]
        }
        long proj2=System.currentTimeMillis()
        def projects2 = projectNames?Execution.createCriteria().list {
            gt('dateStarted', today)
            projections {
                groupProperty('project')
                count()
            }
        }:[]
        projects2 = projects2.findAll{projectNames.contains(it[0])}
        proj2=System.currentTimeMillis()-proj2
        def execCount= 0 //Execution.countByDateStartedGreaterThan( today)
        projects2.each{val->
            if(val.size()==2){
                if(summary[val[0]]) {
                    summary[val[0].toString()].execCount = val[1]
                    projects << val[0]
                    execCount+=val[1]
                }
            }
        }
        def totalFailedCount= 0
        def failedExecs = projectNames? Execution.createCriteria().list {
            gt('dateStarted', today)
            inList('status', ['false', 'failed'])
            projections {
                groupProperty('project')
                count()
            }
        }:[]
        failedExecs = failedExecs.findAll{projectNames.contains(it[0])}
        failedExecs.each{val->
            if(val.size()==2){
                if(summary[val[0]]) {
                    summary[val[0].toString()].failedCount = val[1]
                    totalFailedCount+=val[1]
                }
            }
        }

        long proj3=System.currentTimeMillis()
        def users2 = projectNames?Execution.createCriteria().list {
            gt('dateStarted', today)
            projections {
                distinct('user')
                property('project')
            }
        }:[]
        users2=users2.findAll {projectNames.contains(it[1])}
        proj3=System.currentTimeMillis()-proj3
        def users = new HashSet<String>()
        users2.each{val->
            if(val.size()==2){
                if(summary[val[1]]){
                    summary[val[1]].userSummary<<val[0]
                    summary[val[1]].userCount=summary[val[1]].userSummary.size()
                    users.add(val[0])
                }
            }
        }

        log.debug("loadSummaryProjectStats... ${System.currentTimeMillis()-start}, proj2 ${proj2}, proj3 ${proj3}")
        [summary:summary,recentUsers:users,recentProjects:projects,execCount:execCount,totalFailedCount:totalFailedCount]
    }

    def projectNamesAjax() {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        def fprojects = frameworkService.refreshSessionProjects(authContext, session)

        render(contentType:'application/json',text:
                ([projectNames: fprojects] )as JSON
        )
    }

    def authProjectsToCreateAjax() {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(
                session.subject
        )

        def projectNames = frameworkService.projectNames(authContext)
        def authProjectsToCreate = []
        projectNames.each {
            if (it != params.project && rundeckAuthContextProcessor.authorizeProjectResource(
                    authContext,
                    AuthConstants.RESOURCE_TYPE_JOB,
                    AuthConstants.ACTION_CREATE,
                    it
            )) {
                authProjectsToCreate.add(it)
            }
        }
        render(contentType: 'application/json', text:
                ([projectNames: authProjectsToCreate]) as JSON
        )
    }

    def homeAjax(BaseQuery paging){
        if (requireAjax(action: 'home')) {
            return
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        long start=System.currentTimeMillis()
        //select paged projects to return
        def fprojects
        List<String> projectNames = frameworkService.projectNames(authContext)
        def pagingparams=[:]
        if (null != paging.max && null != paging.offset) {
            if (paging.max > 0 && paging.offset < projectNames.size()) {

                def lastIndex = Math.min(projectNames.size() - 1, paging.offset + paging.max - 1)
                pagingparams = [max: paging.max, offset: paging.offset]
                if (lastIndex + 1 < projectNames.size()) {
                    pagingparams.nextoffset = lastIndex + 1
                } else {
                    pagingparams.nextoffset = -1
                }
                fprojects = projectNames[paging.offset..lastIndex].collect {
                    frameworkService.getFrameworkProject(it)
                }
            } else {
                fprojects = []
            }
        }else if (params.projects) {
            def selected = [params.projects].flatten()
            if(selected.size()==1 && selected[0].contains(',')){
                selected = selected[0].split(',') as List
            }
            fprojects = selected.findAll{projectNames.contains(it)}.collect{
                frameworkService.getFrameworkProject(it)
            }
        } else {
            fprojects = frameworkService.projects(authContext)
        }

        log.debug("frameworkService.projects(context)... ${System.currentTimeMillis()-start}")
        start=System.currentTimeMillis()


        def allsummary=cachedSummaryProjectStats(projectNames).summary
        def summary=[:]
        def durs=[]
        fprojects.each { IRundeckProject project->
            long sumstart=System.currentTimeMillis()
            summary[project.name]=allsummary[project.name]?:[:]
            def description = Project.findByName(project.name)?.description
            if(!description){
                description = project.hasProperty("project.description")?project.getProperty("project.description"):''
                if(description){
                    def proj = Project.findByName(project.name)
                    if(proj){
                        proj.description = description
                        proj.save(flush: true)
                    }
                }
            }
            summary[project.name].label= project.hasProperty("project.label")?project.getProperty("project.label"):''
            summary[project.name].description= description
            def projectAuth = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project.name)
            def eventAuth = rundeckAuthContextProcessor.
                authorizeProjectResource(
                    projectAuth,
                    AuthConstants.RESOURCE_TYPE_EVENT,
                    AuthConstants.ACTION_READ,
                    project.name
                )
            if(!eventAuth){
                summary[project.name].putAll([ execCount: 0, failedCount: 0,userSummary: [], userCount: 0])
            }
            if(!params.refresh) {
                summary[project.name].readmeDisplay = menuService.getReadmeDisplay(project)
                summary[project.name].motdDisplay = menuService.getMotdDisplay(project)
                summary[project.name].readme = frameworkService.getFrameworkProjectReadmeContents(project)
                summary[project.name].executionEnabled =
                    scheduledExecutionService.isRundeckProjectExecutionEnabled(project)
                summary[project.name].scheduleEnabled =
                    scheduledExecutionService.isRundeckProjectScheduleEnabled(project)
                //authorization
                summary[project.name].auth = [
                        jobCreate: rundeckAuthContextProcessor.authorizeProjectResource(
                            projectAuth,
                            AuthConstants.RESOURCE_TYPE_JOB,
                            AuthConstants.ACTION_CREATE,
                            project.name
                        ),
                        admin: rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                            projectAuth,
                            rundeckAuthContextProcessor.authResourceForProject(project.name),
                            [
                                AuthConstants.ACTION_CONFIGURE,
                                AuthConstants.ACTION_ADMIN,
                                AuthConstants.ACTION_APP_ADMIN,
                                AuthConstants.ACTION_IMPORT,
                                AuthConstants.ACTION_EXPORT,
                                AuthConstants.ACTION_DELETE
                            ]
                        ),
                ]
            }
            durs<<(System.currentTimeMillis()-sumstart)
        }


        if(durs.size()>0) {
            def sum=durs.inject(0) { a, b -> a + b }
            log.debug("summarize avg/proj (${durs.size()}) ${sum}ms ... ${sum / durs.size()}")
        }
        render(contentType:'application/json',text:
                (pagingparams + [
                        projects : summary.values(),
                ] )as JSON
        )
    }
    def homeSummaryAjax(){
        if(requireAjax(action: 'home')) {
            return
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        Framework framework = frameworkService.rundeckFramework
        long start=System.currentTimeMillis()
        //select paged projects to return
        List<String> projectNames = frameworkService.projectNames(authContext)

        log.debug("frameworkService.homeSummaryAjax(context)... ${System.currentTimeMillis()-start}")
        start=System.currentTimeMillis()
        def allsummary=cachedSummaryProjectStats(projectNames)
        def projects=allsummary.recentProjects
        def users=allsummary.recentUsers
        def execCount=allsummary.execCount
        def totalFailedCount=allsummary.totalFailedCount

        def fwkNode = framework.getFrameworkNodeName()

        render(contentType: 'application/json', text:
                ([
                        execCount        : execCount,
                        totalFailedCount : totalFailedCount,
                        recentUsers      : users,
                        recentProjects   : projects,
                        frameworkNodeName: fwkNode
                ]) as JSON
        )
    }

    /**
    * API Actions
     */

    @RdAuthorizeSystem(value=RundeckAccess.System.AUTH_READ_OR_OPS_ADMIN,description='Read Logstorage Info')
    def apiLogstorageInfo() {
        if (!apiService.requireApi(request, response, ApiVersions.V17)) {
            return
        }


        def data = logFileStorageService.getStorageStats()
        def propnames = [
                'succeededCount',
                'failedCount',
                'queuedCount',
                'queuedRequestCount',
                'queuedRetriesCount',
                'queuedIncompleteCount',
                'totalCount',
                'incompleteCount',
                'missingCount',
                'retriesCount'
        ]
        withFormat {
            json {
                apiService.renderSuccessJson(response) {
                    enabled = data.pluginName ? true : false
                    pluginName = data.pluginName
                    for (String name : propnames) {
                        delegate.setProperty(name, data[name])
                    }
                }
            }
            xml {

                apiService.renderSuccessXml(request, response) {
                    delegate.'logStorage'(enabled: data.pluginName ? true : false, pluginName: data.pluginName) {
                        for (String name : propnames) {
                            delegate."${name}"(data[name])
                        }
                    }
                }
            }
        }
    }

    @RdAuthorizeSystem(value=RundeckAccess.System.AUTH_READ_OR_OPS_ADMIN,description='Read Logstorage Info')
    def apiLogstorageListIncompleteExecutions(BaseQuery query) {
        if (!apiService.requireApi(request, response, ApiVersions.V17)) {
            return
        }
        query.validate()
        if (query.hasErrors()) {
            return apiService.renderErrorFormat(
                    response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.parameter.error",
                            args: [query.errors.allErrors.collect { message(error: it) }.join("; ")]
                    ]
            )
        }


        def total=logFileStorageService.countIncompleteLogStorageRequests()
        def list = logFileStorageService.listIncompleteRequests(
                frameworkService.serverUUID,
                [max: query.max?:20, offset: query.offset?:0]
        )
        def queuedIncompleteIds=logFileStorageService.getQueuedIncompleteRequestIds()
        def retryIds=logFileStorageService.getQueuedRetryRequestIds()
        def queuedIds=logFileStorageService.getQueuedRequestIds()
        def failedIds=logFileStorageService.getFailedRequestIds()
        withFormat{
            json{
                apiService.renderSuccessJson(response) {
                    delegate.'total' = total
                    max = query.max ?: 20
                    offset = query.offset ?: 0
                    executions = list.collect { LogFileStorageRequest req ->
                        def data=exportRequestMap(
                                req,
                                retryIds.contains(req.id) || queuedIds.contains(req.id) || queuedIncompleteIds.contains(req.id),
                                failedIds.contains(req.id),
                                failedIds.contains(req.id) ? logFileStorageService.getFailures(req.id) : null
                        )
                        [
                                id:data.executionId,
                                project:data.project,
                                href:data.href,
                                permalink:data.permalink,
                                storage:[
                                        localFilesPresent:data.localFilesPresent,
                                        incompleteFiletypes:data.filetype,
                                        queued:data.queued,
                                        failed:data.failed,
                                        date:apiService.w3cDateValue(req.dateCreated),
                                ],
                                errors:data.messages
                        ]
                    }
                }
            }
            xml{
                apiService.renderSuccessXml (request,response) {
                    logstorage {
                        incompleteExecutions(total: total, max: query.max ?: 20, offset: query.offset ?: 0) {
                            list.each { LogFileStorageRequest req ->
                                def data=exportRequestMap(
                                        req,
                                        queuedIds.contains(req.id),
                                        failedIds.contains(req.id),
                                        failedIds.contains(req.id) ? logFileStorageService.getFailures(req.id) : null
                                )
                                execution(id:data.executionId,project:data.project,href:data.href,permalink:data.permalink){
                                    delegate.'storage'(
                                            incompleteFiletypes:data.filetype,
                                            queued:data.queued,
                                            failed:data.failed,
                                            date: apiService.w3cDateValue(req.dateCreated),
                                            localFilesPresent:data.localFilesPresent,
                                    ) {
                                        if(data.messages){
                                            delegate.'errors' {
                                                data.messages.each {
                                                    delegate.'message'(it)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @RdAuthorizeSystem(RundeckAccess.General.AUTH_OPS_ADMIN)
    def apiResumeIncompleteLogstorage() {
        if (!apiService.requireApi(request, response, ApiVersions.V17)) {
            return
        }

        logFileStorageService.resumeIncompleteLogStorageAsync(frameworkService.serverUUID)
        withFormat {
            json {
                apiService.renderSuccessJson(response) {
                    resumed=true
                }
            }
            xml {

                apiService.renderSuccessXml(request, response) {
                    delegate.'logStorage'(resumed:true)
                }
            }
        }
    }


    /**
     * API: /api/jobs, version 1
     */

    /**
     * API: get job info: /api/18/job/{id}/info
     */
    def apiJobDetail() {
        if (!apiService.requireApi(request, response, ApiVersions.V18)) {
            return
        }

        if (!apiService.requireParameters(params, response, ['id'])) {
            return
        }

        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID(params.id)

        if (!apiService.requireExists(response, scheduledExecution, ['Job ID', params.id])) {
            return
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject,
                scheduledExecution.project
        )
        if (!rundeckAuthContextProcessor.authorizeProjectJobAny(
                authContext,
                scheduledExecution,
                [AuthConstants.ACTION_READ,AuthConstants.ACTION_VIEW],
                scheduledExecution.project
        )) {
            return apiService.renderErrorXml(
                    response,
                    [
                            status: HttpServletResponse.SC_FORBIDDEN,
                            code  : 'api.error.item.unauthorized',
                            args  : ['Read', 'Job ID', params.id]
                    ]
            )
        }
        if (!(response.format in ['all', 'xml', 'json'])) {
            return apiService.renderErrorXml(
                    response,
                    [
                            status: HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                            code  : 'api.error.item.unsupported-format',
                            args  : [response.format]
                    ]
            )
        }
        def extra = [:]
        def clusterModeEnabled = frameworkService.isClusterModeEnabled()
        def serverNodeUUID = frameworkService.serverUUID
        if (clusterModeEnabled && scheduledExecution.scheduled) {
            extra.serverNodeUUID = scheduledExecution.serverNodeUUID
            extra.serverOwner = scheduledExecution.serverNodeUUID == serverNodeUUID
        }
        if (scheduledExecution.getAverageDuration()>0) {
            extra.averageDuration = scheduledExecution.getAverageDuration()
        }
        if(jobSchedulesService.shouldScheduleExecution(scheduledExecution.uuid)){
            extra.nextScheduledExecution=scheduledExecutionService.nextExecutionTime(scheduledExecution)
        }
        respond(

                JobInfo.from(
                        scheduledExecution,
                        apiService.apiHrefForJob(scheduledExecution),
                        apiService.guiHrefForJob(scheduledExecution),
                        extra
                ),

                [formats: ['xml', 'json']]
        )
    }


    def apiJobForecast() {
        if (!apiService.requireApi(request, response, ApiVersions.V31)) {
            return
        }

        if (!apiService.requireParameters(params, response, ['id'])) {
            return
        }

        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID(params.id)

        if (!apiService.requireExists(response, scheduledExecution, ['Job ID', params.id])) {
            return
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject,
                scheduledExecution.project
        )
        if (!rundeckAuthContextProcessor.authorizeProjectJobAny(
                authContext,
                scheduledExecution,
                [AuthConstants.ACTION_READ, AuthConstants.ACTION_VIEW],
                scheduledExecution.project
        )) {
            return apiService.renderErrorXml(
                    response,
                    [
                            status: HttpServletResponse.SC_FORBIDDEN,
                            code  : 'api.error.item.unauthorized',
                            args  : ['Read', 'Job ID', params.id]
                    ]
            )
        }
        if (!(response.format in ['all', 'xml', 'json'])) {
            return apiService.renderErrorXml(
                    response,
                    [
                            status: HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                            code  : 'api.error.item.unsupported-format',
                            args  : [response.format]
                    ]
            )
        }

        def extra = [:]

        //future scheduled executions forecast
        def time = params.time? params.time : '1d'

        def retro = ((request.api_version >= ApiVersions.V32) && (params.past=='true'))

        Date futureDate = futureRelativeDate(time,retro)

        def max = null
        if (params.max) {
            max = params.int('max')
            if (max <= 0) {
                max = null
            }
        }

        if (jobSchedulesService.shouldScheduleExecution(scheduledExecution.uuid)) {
            extra.futureScheduledExecutions = scheduledExecutionService.nextExecutions(scheduledExecution, futureDate, retro)
            if (max
                    && extra.futureScheduledExecutions
                    && extra.futureScheduledExecutions.size() > max) {
                extra.futureScheduledExecutions = extra.futureScheduledExecutions[0..<max]
            }
        }


        respond(

                JobInfo.from(
                        scheduledExecution,
                        apiService.apiHrefForJob(scheduledExecution),
                        apiService.guiHrefForJob(scheduledExecution),
                        extra
                ),

                [formats: ['xml', 'json']]
        )
    }

    private Date futureRelativeDate(String recentFilter, boolean negative=false){
        Calendar n = GregorianCalendar.getInstance()
        n.setTime(new Date())
        def matcher = recentFilter =~ /^(\d+)([hdwmyns])$/
        if (matcher.matches()) {
            def i = matcher.group(1).toInteger()
            def ndx
            switch (matcher.group(2)) {
                case 'h':
                    ndx = Calendar.HOUR_OF_DAY
                    break
                case 'n':
                    ndx = Calendar.MINUTE
                    break
                case 's':
                    ndx = Calendar.SECOND
                    break
                case 'd':
                    ndx = Calendar.DAY_OF_YEAR
                    break
                case 'w':
                    ndx = Calendar.WEEK_OF_YEAR
                    break
                case 'm':
                    ndx = Calendar.MONTH
                    break
                case 'y':
                    ndx = Calendar.YEAR
                    break
            }
            if(negative){
                n.add(ndx, i*-1)
            }else {
                n.add(ndx, i)
            }
            return n.getTime()
        }
        null
    }

    private void respondApiJobsList(List<ScheduledExecution> results) {
        def clusterModeEnabled = frameworkService.isClusterModeEnabled()
        def serverNodeUUID = frameworkService.serverUUID

        if (request.api_version >= ApiVersions.V18) {
            //new response format mechanism
            //no <result> tag
            def data = new JobInfoList(
                    results.collect { ScheduledExecution se ->
                        Map data = [:]
                        if (clusterModeEnabled) {
                            data = [
                                    serverNodeUUID: se.serverNodeUUID,
                                    serverOwner   : se.serverNodeUUID == serverNodeUUID
                            ]
                        }
                        JobInfo.from(
                                se,
                                apiService.apiHrefForJob(se),
                                apiService.guiHrefForJob(se),
                                data
                        )
                    }
            )
            respond(
                    data,
                    [formats: ['xml', 'json']]
            )
            return
        }
        withFormat {
            def xmlresponse= {
                return apiService.renderSuccessXml(request, response) {
                    delegate.'jobs'(count: results.size()) {
                        results.each { ScheduledExecution se ->
                            def jobparams = [id: se.extid, href: apiService.apiHrefForJob(se),
                                             permalink: apiService.guiHrefForJob(se)]
                            if (request.api_version >= ApiVersions.V17) {
                                jobparams.scheduled = se.scheduled
                                jobparams.scheduleEnabled = se.scheduleEnabled
                                jobparams.enabled = se.executionEnabled
                                if (clusterModeEnabled && se.scheduled) {
                                    jobparams.serverNodeUUID = se.serverNodeUUID
                                    jobparams.serverOwner = jobparams.serverNodeUUID == serverNodeUUID
                                }
                            }
                            job(jobparams) {
                                name(se.jobName)
                                group(se.groupPath)
                                project(se.project)
                                description(se.description)
                            }
                        }
                    }
                }
            }
            xml xmlresponse
            json {
                return apiService.renderSuccessJson(response) {
                    results.each { ScheduledExecution se ->
                        def jobparams = [id         : se.extid,
                                         name       : (se.jobName),
                                         group      : (se.groupPath),
                                         project    : (se.project),
                                         description: (se.description),
                                         href       : apiService.apiHrefForJob(se),
                                         permalink  : apiService.guiHrefForJob(se)]
                        if (request.api_version >= ApiVersions.V17) {
                            jobparams.scheduled = se.scheduled
                            jobparams.scheduleEnabled = se.scheduleEnabled
                            jobparams.enabled = se.executionEnabled
                            if (clusterModeEnabled && se.scheduled) {
                                jobparams.serverNodeUUID = se.serverNodeUUID
                                jobparams.serverOwner = jobparams.serverNodeUUID == serverNodeUUID
                            }
                        }
                        element(jobparams)
                    }
                }
            }
            '*' xmlresponse
        }
    }
    /**
     * Require server UUID and list all owned jobs
     * /api/$api_version/scheduler/server/$uuid/jobs and
     * /api/$api_version/scheduler/jobs
     * @return
     */
    def apiSchedulerListJobs(String uuid, boolean currentServer) {
        if (!apiService.requireApi(request, response, ApiVersions.V17)) {
            return
        }
        if(currentServer) {
            uuid = frameworkService.serverUUID
        }
        def query = new ScheduledExecutionQuery(serverNodeUUIDFilter: uuid, scheduledFilter: true)
        query.validate()
        if (query.hasErrors()) {
            return apiService.renderErrorFormat(
                    response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.parameter.error",
                            args: [query.errors.allErrors.collect { message(error: it) }.join("; ")]
                    ]
            )
        }


        def list = jobSchedulesService.getAllScheduled(uuid)
        //filter authorized jobs
        Map<String, UserAndRolesAuthContext> projectAuths = [:]
        def authForProject = { String project ->
            if (projectAuths[project]) {
                return projectAuths[project]
            }
            projectAuths[project] = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project)
            projectAuths[project]
        }
        def authorized = list.findAll { ScheduledExecution se ->
            rundeckAuthContextProcessor.authorizeProjectJobAny(
                    authForProject(se.project),
                    se,
                    [AuthConstants.ACTION_READ,AuthConstants.ACTION_VIEW],
                    se.project
            )
        }

        respondApiJobsList(authorized)
    }
    /**
     * API: /api/2/project/NAME/jobs, version 2
     */
    def apiJobsListv2 (ScheduledExecutionQuery query) {
        if (!apiService.requireApi(request, response)) {
            return
        }
        if(!params.project){
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                                                           code: 'api.error.parameter.required', args: ['project']])

        }

        query.projFilter = params.project
        //test valid project

        if (!apiService.requireExists(
                response,
                frameworkService.existsFrameworkProject(params.project),
                ['project', params.project]
        )) {
            return
        }
        if(null!=query.scheduledFilter || null!=query.serverNodeUUIDFilter){
            if (!apiService.requireApi(request,response,ApiVersions.V17)) {
                return
            }
        }
        if(null!=query.scheduleEnabledFilter || null!=query.executionEnabledFilter){
            if (!apiService.requireApi(request,response,ApiVersions.V18)) {
                return
            }
        }

        if (request.api_version < ApiVersions.V14 && !(response.format in ['all','xml'])) {
            return apiService.renderErrorFormat(response,[
                    status:HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    code: 'api.error.item.unsupported-format',
                    args: [response.format]
            ])
        }
        if(query.hasErrors()){
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.parameter.error",
                            args: [query.errors.allErrors.collect { message(error: it) }.join("; ")]
                    ])
        }
        def results = jobsFragment(query, JobsScmInfo.NONE)

        respondApiJobsList(results.nextScheduled)
    }

    /**
     * API: /api/14/project/NAME/jobs/export
     */
    def apiJobsExportv14 (ScheduledExecutionQuery query){
        if(!apiService.requireApi(request,response,ApiVersions.V14)){
            return
        }

        if(!params.project){
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.parameter.required', args: ['project']])
        }
        query.projFilter = params.project
        //test valid project
        Framework framework = frameworkService.getRundeckFramework()


        if (!apiService.requireExists(
            response,
            frameworkService.existsFrameworkProject(params.project),
            ['project', params.project]
        )) {
            return
        }
        //don't load scm status for api response
        def results = jobsFragment(query,JobsScmInfo.NONE)

        withFormat{
            xml{
                def writer = new StringWriter()
                rundeckJobDefinitionManager.exportAs('xml',results.nextScheduled, writer)
                writer.flush()
                render(text:writer.toString(),contentType:"text/xml",encoding:"UTF-8")
            }
            yaml{
                final def encoded = JobsYAMLCodec.encode(results.nextScheduled as List)
                render(text:encoded,contentType:"text/yaml",encoding:"UTF-8")
            }
        }
    }

    /**
     * API: /project/PROJECT/executions/running, version 14
     */
    def apiExecutionsRunningv14 (){
        if(!apiService.requireApi(request,response,ApiVersions.V14)) {
            return
        }

        if (!params.project) {
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                                                           code  : 'api.error.parameter.required', args: ['project']])
        }

        //allow project='*' to indicate all projects
        def allProjects = params.project == '*'
        //test valid project
        if (!allProjects) {
            if (!apiService.requireExists(response, frameworkService.existsFrameworkProject(params.project), ['project', params.project])) {
                return
            }
            if (!apiAuthorizedForEventRead(params.project)) {
                return
            }
        }
        if (request.api_version < ApiVersions.V14 && !(response.format in ['all', 'xml'])) {
            return apiService.renderErrorXml(response, [
                    status: HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    code  : 'api.error.item.unsupported-format',
                    args  : [response.format]
            ])
        }

        def projectNameAuthorized = "";

        if (allProjects){
            AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
            def authorized = listProjectsEventReadAuthorized(authContext)

            if (!authorized || authorized.isEmpty()) {
                return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_UNAUTHORIZED,
                                                               code  : 'api.error.execution.project.notfound', args: [params.project]])
            }

            projectNameAuthorized = authorized.join(',')
        } else {
            projectNameAuthorized = params.project
        }

        QueueQuery query = new QueueQuery(
            considerPostponedRunsAsRunningFilter: false, // by default do not include scheduled or queued executions.
            runningFilter: 'running',
            projFilter: projectNameAuthorized)
        if (params.max) {
            query.max = params.int('max')
        }
        if (params.offset) {
            query.offset = params.int('offset')
        }

        if(params.includePostponed) {
            query.considerPostponedRunsAsRunningFilter = true
        }

        if (request.api_version >= ApiVersions.V31 && params.jobIdFilter) {
            query.jobIdFilter = params.jobIdFilter
        }

        def results = nowrunning(query)

        withFormat{
            xml {
                return executionService.respondExecutionsXml(
                        request,
                        response,
                        results.nowrunning,
                        [
                                total: results.total,
                                offset: results.offset,
                                max: results.max
                        ]
                )
            }
            json {
                return executionService.respondExecutionsJson(
                        request,
                        response,
                        results.nowrunning,
                        [
                                total: results.total,
                                offset: results.offset,
                                max: results.max
                        ]
                )
            }
        }

    }

    @CompileStatic
    protected List<String> listProjectsEventReadAuthorized(AuthContext authContext){
        return frameworkService.projectNames(authContext).findAll{String name->
            apiAuthorizedForEventRead(name)
        }
    }

    def listExport(){
        UserAndRolesAuthContext authContext

        def results=[:]
        if(request.format=='json' ) {
            def data = request.JSON
            def nextScheduled = data?.join(",")?.replaceAll(/"/, '')
            def query = new ScheduledExecutionQuery()
            query.idlist = nextScheduled //request.format   def data= request.JSON
            query.projFilter = params.project
            authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
            def result = listWorkflows(query, authContext, session.user)


            if (rundeckAuthContextProcessor.authorizeApplicationResourceAny(authContext,
                    rundeckAuthContextProcessor.authResourceForProject(
                            params.project
                    ),
                    [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN, AuthConstants.ACTION_EXPORT,  AuthConstants.ACTION_SCM_EXPORT]
            )) {
                def pluginData = [:]
                try {
                    if (scmService.projectHasConfiguredExportPlugin(params.project)) {
                        pluginData.scmExportEnabled = scmService.loadScmConfig(params.project, 'export')?.enabled
                        if (pluginData.scmExportEnabled) {
                            def jobsPluginMeta = scmService.getJobsPluginMeta(params.project, true)
                            pluginData.scmStatus = scmService.exportStatusForJobs(params.project, authContext, result.nextScheduled, false, jobsPluginMeta)
                            pluginData.scmExportStatus = scmService.exportPluginStatus(authContext, params.project)
                            pluginData.scmExportActions = scmService.exportPluginActions(authContext, params.project)
                            pluginData.scmExportRenamed = scmService.getRenamedJobPathsForProject(params.project)
                        }
                        results.putAll(pluginData)
                    }
                } catch (ScmPluginException e) {
                    results.warning = "Failed to update SCM Export status: ${e.message}"
                }
            }
            if (rundeckAuthContextProcessor.authorizeApplicationResourceAny(authContext,
                    rundeckAuthContextProcessor.authResourceForProject(
                            params.project
                    ),
                    [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN, AuthConstants.ACTION_IMPORT, AuthConstants.ACTION_SCM_IMPORT]
            )) {

                def pluginData = [:]
                try {
                    if (scmService.projectHasConfiguredImportPlugin(params.project)) {
                        pluginData.scmImportEnabled = scmService.loadScmConfig(params.project, 'import')?.enabled
                        if (pluginData.scmImportEnabled) {
                            def jobsPluginMeta = scmService.getJobsPluginMeta(params.project, false)
                            pluginData.scmImportJobStatus = scmService.importStatusForJobs(params.project, authContext, result.nextScheduled,false, jobsPluginMeta)
                            pluginData.scmImportStatus = scmService.importPluginStatus(authContext, params.project)
                            pluginData.scmImportActions = scmService.importPluginActions(authContext, params.project, pluginData.scmImportStatus)
                        }
                        results.putAll(pluginData)
                    }

                } catch (ScmPluginException e) {
                    results.warning = "Failed to update SCM Import status: ${e.message}"
                }
            }
        }
        render(results as JSON)
    }


    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_CONFIGURE)
    def projectToggleSCM(){
        def ePluginConfig = scmService.loadScmConfig(params.project, 'export')
        def iPluginConfig = scmService.loadScmConfig(params.project, 'import')
        def eConfiguredPlugin = null
        def iConfiguredPlugin = null
        if (ePluginConfig?.type) {
            eConfiguredPlugin = scmService.getPluginDescriptor('export', ePluginConfig.type)
        }
        if (iPluginConfig?.type) {
            iConfiguredPlugin = scmService.getPluginDescriptor('import', iPluginConfig.type)
        }
        def eEnabled = ePluginConfig?.enabled && scmService.projectHasConfiguredPlugin('export', params.project)
        def iEnabled = iPluginConfig?.enabled && scmService.projectHasConfiguredPlugin('import', params.project)

        if(eEnabled || iEnabled){
            //at least one active plugin, disable
            if(eConfiguredPlugin){
                scmService.disablePlugin('export', params.project, eConfiguredPlugin.name)
            }
            if(iConfiguredPlugin){
                scmService.disablePlugin('import', params.project, iConfiguredPlugin.name)
            }
        }else{
            if(eConfiguredPlugin){
                scmService.enablePlugin(projectAuthContext, 'export', params.project, eConfiguredPlugin.name)
            }
            if(iConfiguredPlugin){
                scmService.enablePlugin(projectAuthContext, 'import', params.project, iConfiguredPlugin.name)
            }
        }
        return redirect(controller:'menu',action:'jobs', params: [project: params.project])
    }

    def userSummary(){
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        if(unauthorizedResponse(rundeckAuthContextProcessor.authorizeApplicationResourceAny(authContext, AuthConstants.RESOURCE_TYPE_USER,
                [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]),
                AuthConstants.ACTION_ADMIN, 'User', 'accounts')) {
            return
        }
        [users: [:]]
    }

}
