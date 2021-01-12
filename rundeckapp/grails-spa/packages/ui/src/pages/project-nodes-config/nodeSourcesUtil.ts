import { client } from '../../services/rundeckClient'
import yaml from 'js-yaml';

import {
  getRundeckContext,
  RundeckContext
} from "@rundeck/ui-trellis"

export interface NodeSourceResources {
  href: string
  editPermalink?: string
  writeable: boolean
  description?: string
  syntaxMimeType?:string
  empty?: boolean
}
export interface NodeSource {
  index: number
  type: string
  resources: NodeSourceResources
  errors?:string
}
export interface StorageAccess{
  authorized: boolean;
  action: string;
  description: string;
}
export async function getProjectNodeSources(): Promise<NodeSource[]> {

  const rundeckContext = getRundeckContext()
  const resp = await client.sendRequest({
    pathTemplate: '/api/{apiVersion}/project/{projectName}/sources',
    pathParameters: rundeckContext,
    baseUrl: rundeckContext.rdBase,
    method: 'GET'
  })
  if (!resp.parsedBody) {
    throw new Error(`Error getting node sources list for ${rundeckContext.projectName}`)
  }
  else {
    return resp.parsedBody as NodeSource[]
  }
}

export async function createProjectAcl( aclDescription: string): Promise<void> {
  const rundeckContext = getRundeckContext()

  const aclDefinition = {
    description: aclDescription,
    context: {
      application: 'rundeck'
    },
    by: {
      urn: `project:${rundeckContext.projectName}`
    },
    for: {
      storage: [
        {allow: '*'}
      ]
    }
  };
  const aclContent = yaml.dump(aclDefinition);
  const name = `keystorage-access-${rundeckContext.projectName}.aclpolicy`

  const content = {
    "contents": aclContent
  }

  const resp = await rundeckContext.rundeckClient.sendRequest({
    pathTemplate: '/api/{apiVersion}/system/acl/{aclName}',
    pathParameters: {aclName: name, apiVersion: rundeckContext.apiVersion},
    baseUrl: rundeckContext.rdBase,
    body: content,
    method: 'POST'
  })
  if (resp.status!==201) {
    throw new Error(`Error creating acl for project ${rundeckContext.projectName}`)
  }

}

export async function getProjectStorageAccess(): Promise<StorageAccess> {

  const rundeckContext = getRundeckContext()
  const resp = await rundeckContext.rundeckClient.sendRequest({
    pathTemplate: 'project/{projectName}/nodes/sources/storageaccess',
    pathParameters: rundeckContext,
    baseUrl: rundeckContext.rdBase,
    method: 'POST'
  })
  if (!resp.parsedBody) {
    throw new Error(`Error getting node sources list for ${rundeckContext.projectName}`)
  }
  if(resp.status !== 200){
    return {authorized: false} as StorageAccess;
  }
  else {
    const body = resp.parsedBody;
    return body["keys/"] as StorageAccess;
  }
}

