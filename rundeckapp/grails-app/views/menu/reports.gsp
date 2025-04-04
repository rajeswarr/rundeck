<%--
   reports.gsp
--%>

<%@ page import="org.rundeck.core.auth.AuthConstants;rundeck.User; grails.util.Environment" %>
<html>
<head>
    <g:set var="rkey" value="${g.rkey()}" />
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <meta name="layout" content="base"/>
    <meta name="tabpage" content="reports"/>
    <g:set var="projectName" value="${params.project ?: request.project}"/>
    <g:set var="projectLabel" value="${session.frameworkLabels?session.frameworkLabels[projectName]:projectName}"/>

    <title>Reports - <g:enc>${projectLabel}</g:enc></title>
</head>
<body>
    <div id="reports-container">

    </div>
</body>
</html>