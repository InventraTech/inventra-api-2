package com.inventra.api.core.service.passwordreset;

import com.inventra.api.core.service.passwordreset.model.request.ForgotPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.ResetPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.VerifyResetCodeRequest;
import com.inventra.api.core.service.passwordreset.model.response.ResetTokenResponse;

// "Esqueci a senha", para o app e o web. O e-mail leva um código de 6 dígitos e um link com token:
// o código passa por verify e vira um token; o link já traz um. Os dois terminam em reset.
public interface PasswordResetUseCase {

    // Não diz se o e-mail tem conta: a resposta é a mesma em todos os casos.
    void forgot(ForgotPasswordRequest request);

    ResetTokenResponse verify(VerifyResetCodeRequest request);

    void reset(ResetPasswordRequest request);
}
