%{--
  - Copyright 2019 Rundeck, Inc. (https://www.rundeck.com)
  -
  - Licensed under the Apache License, Version 2.0 (the "License");
  - you may not use this file except in compliance with the License.
  - You may obtain a copy of the License at
  -
  -     http://www.apache.org/licenses/LICENSE-2.0
  -
  - Unless required by applicable law or agreed to in writing, software
  - distributed under the License is distributed on an "AS IS" BASIS,
  - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  - See the License for the specific language governing permissions and
  - limitations under the License.
  --}%
<%--
   status.gsp

   Author: Jesse Marple
   Created: August 26th, 2019
   $Id$
--%>
<%@ page contentType="text/html" %>
<g:set var="emailCSSFrameworkEnabled" value="${cfg.getString(config: "feature.emailCSSFramework.enabled") in [true,'true']}"/>

<g:if test="${emailCSSFrameworkEnabled == 'true'}">
  <g:render template="/execution/mailNotification/newStatus"/>
</g:if>
<g:else>
  <g:render template="/execution/mailNotification/oldStatus"/>
</g:else>
