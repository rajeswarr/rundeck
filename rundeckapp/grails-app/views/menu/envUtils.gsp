<%--
   envUtils.gsp
   Author: Rajeswar Reddy Gaulla
   Created: 27/07/2021 
--%>

<%@ page import="org.rundeck.core.auth.AuthConstants;rundeck.User; grails.util.Environment" %>
<html>
<head>
    <g:set var="rkey" value="${g.rkey()}" />
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <meta name="layout" content="base"/>
    <meta name="tabpage" content="envUtils"/>
    <g:set var="projectName" value="${params.project ?: request.project}"/>
    <g:set var="projectLabel" value="${session.frameworkLabels?session.frameworkLabels[projectName]:projectName}"/>

    <title>EnvUtils- <g:enc>${projectLabel}</g:enc></title>
</head>
<body>
    <div id='envutils-container'>

    </div>
</body>
</html>