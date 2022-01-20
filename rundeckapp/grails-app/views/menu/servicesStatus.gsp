<%--
   servicesStatus.gsp
   Author: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
   Created: 6/1/11 9:44 AM
--%>

<%@ page import="org.rundeck.core.auth.AuthConstants;rundeck.User; grails.util.Environment" %>
<html>
<head>
    <g:set var="rkey" value="${g.rkey()}" />
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <meta name="layout" content="base"/>
    <meta name="tabpage" content="servicesStatus"/>
    <g:set var="projectName" value="${params.project ?: request.project}"/>
    <g:set var="projectLabel" value="${session.frameworkLabels?session.frameworkLabels[projectName]:projectName}"/>

    <title>ServicesStatus - <g:enc>${projectLabel}</g:enc></title>
</head>
<body>
    <div id='services-status-container'>

    </div>
</body>
</html>