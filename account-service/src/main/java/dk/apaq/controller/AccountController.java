package dk.apaq.controller;

import dk.apaq.jwutil.common.controller.BaseController;
import dk.apaq.jwutil.common.controller.TreeNodeHolder;
import dk.apaq.jwutil.common.errors.ResourceNotFoundException;
import dk.apaq.jwutil.common.filter.UnitIdHeaderFilter;
import dk.apaq.model.Account;
import dk.apaq.model.NewPasswordRequest;
import dk.apaq.model.Roles;
import dk.apaq.model.SecurityQuestionAnswer;
import dk.apaq.model.SecurityQuestionInformation;
import dk.apaq.model.SecurityQuestionRequest;
import dk.apaq.security.SecurityHelper;
import dk.apaq.service.AccountService;
import dk.apaq.jwutil.common.security.Token;
import dk.apaq.jwutil.common.security.TokenProvider;
import dk.apaq.service.RecaptchaService;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
public class AccountController extends BaseController<Account, AccountService> {

    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);
    private static final String PARAMETER_NAME_RECAPTCHA_RESPONSE = "X-Recaptcha-Response";
    private static final String PARAMETER_NAME_REMOTE_IP = "X-Forwarded-For";
    private static final int FOREVER_IN_MINUTES = 52560000;  // 100 years

    @Autowired
    private AccountService accountService;

    @Autowired
    private RecaptchaService recaptchaService;
    
    @Autowired
    private TokenProvider tokenProvider;
    

    @RequestMapping(value = "/accounts", method = RequestMethod.GET)
    public ResponseEntity<List<Account>> list(WebRequest request) {
        LOG.debug("List Accounts request");
        
        Pageable pageable = resolvePageRequest(request, "login", "name", "email");
        Page<Account> page;
        
        String unitId = UnitIdHeaderFilter.getCurrentUnitId();
        
        if (unitId == null) {
            page = accountService.findAll(pageable);
        } else {
            page = accountService.findAllByUnit(unitId, pageable);
        }
        
        return handlePage(page);
    }
    
    @RequestMapping(value = "/accounts/current/actions/password", method = {RequestMethod.POST})
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Secured("ROLE_USER")
    public void changePassword(@RequestBody NewPasswordRequest newPasswordRequest) {
        LOG.debug("Changing password for user");
        accountService.changePassword(newPasswordRequest.getCurrentPassword(), newPasswordRequest.getNewPassword());
    }

    @RequestMapping(value = "/accounts/actions/authenticate", method = {RequestMethod.GET, RequestMethod.POST})
    @Secured("ROLE_USER")
    public Token authenticate() {
        LOG.debug("Authenticating user");
        
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails details = (UserDetails) authentication.getPrincipal();
        Account account = SecurityHelper.getCurrentAccount();
        
        if(account == null) {
            throw new AccessDeniedException("No account for the specified credentials.");
        }
        
        // if user is purely a public token for an unit we let it last "forever"
        List<String> orgRoles = Roles.resolveRolesByPrefix(account.getRoles(), Roles.PREFIX_UNITROLE);
        if(orgRoles.size() == 1) {
            String singleRole = orgRoles.get(0);
            if(singleRole.endsWith(Roles.SEPARATOR + Roles.ORGROLE_PUBLIC)) {
                return tokenProvider.createToken(details, FOREVER_IN_MINUTES);
            }
        }
        return tokenProvider.createToken(details);
    }
    
    @RequestMapping(value = "/accounts", method = RequestMethod.POST)
    public Account create(WebRequest request, @RequestBody Account account) {
        return doCreate(account, request);
    }
    
    @RequestMapping(value = "/accounts", method = RequestMethod.POST, consumes = "application/x-www-form-urlencoded")
    public Account createViaForm(WebRequest request, @ModelAttribute Account account) {
        return doCreate(account, request);
    }
    
    @RequestMapping(value = "/accounts/{id}", method = RequestMethod.GET)
    public Account get(@PathVariable String id) {
        return checkResource(doGet(id));
    }
    
    @RequestMapping(value = "/accounts/{id}", method = {RequestMethod.PUT, RequestMethod.POST})
    public Account update(@RequestBody Account account, @PathVariable String id) {
        if(id.equals("current") && SecurityHelper.getCurrentAccount() != null) {
            id = SecurityHelper.getCurrentAccount().getId();
        }
        return doUpdate(id, account, treeNodePropertyReferenceConverter.translate(TreeNodeHolder.get()));
    }
    
    @RequestMapping(value = "/accounts/{id}", method = {RequestMethod.PUT, RequestMethod.POST}, consumes = "application/x-www-form-urlencoded")
    public Account updateViaForm(@PathVariable String id, @ModelAttribute Account account, HttpServletRequest request) {
        if(id.equals("current") && SecurityHelper.getCurrentAccount() != null) {
            id = SecurityHelper.getCurrentAccount().getId();
        }
        return doUpdate(id, account, formPropertyReferenceConverter.translate(request.getParameterMap()));
    }
    
    @RequestMapping(value = "/accounts/{id}", method = RequestMethod.DELETE)
    @ResponseStatus(value = HttpStatus.OK)
    public void delete(@PathVariable String id) {
        doDelete(id);
    }
    
    @RequestMapping(value = "/accounts/retrieveSecurityQuestionType", method = RequestMethod.POST)
    public SecurityQuestionInformation getQuestionType(WebRequest request, @RequestBody SecurityQuestionRequest questionRequest) {
        String recaptchaResponse = request.getHeader(PARAMETER_NAME_RECAPTCHA_RESPONSE);
        String remoteIp = request.getHeader(PARAMETER_NAME_REMOTE_IP);
        
        SecurityQuestionInformation resetInformation = accountService.getSecurityQuestionType(questionRequest.getLogin(), questionRequest.getEmail(), 
                recaptchaResponse, remoteIp);
        if(resetInformation == null) {
            throw new ResourceNotFoundException("No question type found for the specified login.");
        }
        
        return resetInformation;
    }
    
    @RequestMapping(value = "/accounts/actions/password", method = RequestMethod.POST)
    @ResponseStatus(value = HttpStatus.OK)
    public void regeneratePassword(@RequestBody SecurityQuestionAnswer answer) {
        accountService.regeneratePassword(answer.getAccountId(), answer.getAnswer(), answer.getNewPassword());
    }
    
    
    @RequestMapping(value = "/accounts/{id}/validateEmail", method = RequestMethod.POST)
    @ResponseStatus(value = HttpStatus.OK)
    public void emailValidation(@RequestParam(required = false) String token, @PathVariable String id) {
        LOG.debug("Email validation. [id={}; token={}]", id, token);
        
        
        if(token == null) {
            Account account = accountService.findOne(id);
            accountService.initiateEmailValidation(account);
        } else {
            accountService.completeEmailValidation(id, token);
        }
    }
    
    public Account doCreate(Account entity, WebRequest request) {
        LOG.debug("Create account request [account={}]", entity);
        entity.setId(null); // Make sure that the account is a new account
        
        String recaptchaResponse = request.getHeader(PARAMETER_NAME_RECAPTCHA_RESPONSE);
        String remoteIp = request.getHeader(PARAMETER_NAME_REMOTE_IP);
            
        return accountService.create(entity, recaptchaResponse, remoteIp);
    }
    
    
    

}
