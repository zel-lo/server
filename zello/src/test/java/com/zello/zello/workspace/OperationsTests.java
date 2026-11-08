package com.zello.zello.workspace;
import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.repository.RepositoryService;
import com.zello.zello.integration.github.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class OperationsTests {
    @Test void successfulBuildDoesNotImplySuccessfulDeployment() {
        var repos=mock(RepositoryService.class);var github=mock(GitHubOAuthRepositories.class);var tokens=mock(GitHubTokenProvider.class);var principal=mock(ZelloPrincipal.class);
        when(principal.userId()).thenReturn(1L);
        when(repos.list(2,1)).thenReturn(List.of(new RepositoryService.RepositoryView("3","2","4",null,"team","repo","https://github.com/team/repo",null,null,"main",true,"CONNECTED",false)));
        when(tokens.oauthToken(any(),any())).thenReturn("test-token");
        when(github.repository("test-token",4)).thenReturn(new GitHubGateway.RemoteRepository(4,new GitHubGateway.Owner(5,"team","Organization",null),"repo","team/repo","https://github.com/team/repo",null,null,"main",true,false));
        var builder=RestClient.builder().baseUrl("https://api.github.com");var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/team/repo/actions/runs?per_page=10")).andExpect(header("Authorization","Bearer test-token")).andRespond(withSuccess("{\"workflow_runs\":[{\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"success\",\"head_branch\":\"main\"}]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/team/repo/deployments?per_page=10")).andRespond(withSuccess("[{\"id\":12,\"environment\":\"production\",\"sha\":\"abc\"}]",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/team/repo/deployments/12/statuses?per_page=1")).andRespond(withSuccess("[{\"state\":\"failure\"}]",MediaType.APPLICATION_JSON));
        var controller=new OperationsController(repos,github,tokens,builder.build());
        var response=(ResponseEntity<?>)controller.read(2,"3",principal,null,new MockHttpServletRequest());
        var body=(OperationsController.Operations)response.getBody();
        assertThat(body.builds().getFirst().conclusion()).isEqualTo("success");
        assertThat(body.deployments().getFirst().status()).isEqualTo("failure");server.verify();
    }
}
