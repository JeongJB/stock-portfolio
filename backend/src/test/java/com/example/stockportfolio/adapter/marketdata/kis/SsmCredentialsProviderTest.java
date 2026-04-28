package com.example.stockportfolio.adapter.marketdata.kis;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsmCredentialsProviderTest {

    @Test
    void SecureString_을_캐싱하고_2회차는_SSM을_호출하지_않는다() {
        SsmClient client = mock(SsmClient.class);
        Function<String, String> values = name -> switch (name) {
            case "/portfolio/kis/app-key" -> "K";
            case "/portfolio/kis/app-secret" -> "S";
            default -> throw new IllegalArgumentException(name);
        };
        when(client.getParameter(any(GetParameterRequest.class)))
                .thenAnswer(inv -> {
                    GetParameterRequest req = inv.getArgument(0);
                    assertThat(req.withDecryption()).isTrue();
                    return GetParameterResponse.builder()
                            .parameter(Parameter.builder().name(req.name()).value(values.apply(req.name())).build())
                            .build();
                });
        SsmCredentialsProvider provider = new SsmCredentialsProvider(
                client,
                "/portfolio/kis/app-key",
                "/portfolio/kis/app-secret");

        KisCredentialsProvider.KisCredentials first = provider.get();
        KisCredentialsProvider.KisCredentials second = provider.get();

        assertThat(first.appKey()).isEqualTo("K");
        assertThat(first.appSecret()).isEqualTo("S");
        assertThat(second).isSameAs(first);
        verify(client, times(2)).getParameter(any(GetParameterRequest.class));
    }
}
