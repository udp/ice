package org.jbei.ice.services.rest;

import org.jbei.ice.lib.account.UserApiKeys;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * REST Resource for api-keys that enable third-party application access to ice
 *
 * @author Hector Plahar
 */
@Path("/api-keys")
public class ApiKeyResource extends RestResource {

    /**
     * creates an api-key for the specified client id
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createApiKey(@HeaderParam(AUTHENTICATION_PARAM_NAME) String sessionId,
                                 @QueryParam("client_id") String clientId) {
        String userId = getUserId(sessionId);
        UserApiKeys apiKeys = new UserApiKeys(userId);
        return super.respond(apiKeys.requestKey(clientId));
    }

    /**
     * retrieves list of api keys created by user
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApiKeys(@HeaderParam(AUTHENTICATION_PARAM_NAME) String sessionId,
                               @DefaultValue("0") @QueryParam("offset") int offset,
                               @DefaultValue("15") @QueryParam("limit") int limit,
                               @DefaultValue("true") @QueryParam("asc") boolean asc,
                               @DefaultValue("creationTime") @QueryParam("sort") String sort,
                               @QueryParam("client_id") String clientId) {
        String userId = getUserId(sessionId);
        UserApiKeys apiKeys = new UserApiKeys(userId);
        return super.respond(apiKeys.getKeys(limit, offset, sort, asc));
    }
}
