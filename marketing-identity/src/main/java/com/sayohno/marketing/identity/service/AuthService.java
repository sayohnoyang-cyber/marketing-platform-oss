package com.sayohno.marketing.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamMerchant;
import com.sayohno.marketing.identity.dao.entity.IamMerchantMember;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMemberMapper;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import com.sayohno.marketing.identity.dao.mapper.IamUserMapper;
import com.sayohno.marketing.identity.session.SessionStore;
import com.sayohno.marketing.identity.session.UserSession;
import com.sayohno.marketing.identity.web.dto.AddMemberRequest;
import com.sayohno.marketing.identity.web.dto.CreateShopRequest;
import com.sayohno.marketing.identity.web.dto.LoginRequest;
import com.sayohno.marketing.identity.web.dto.LoginResponse;
import com.sayohno.marketing.identity.web.dto.MemberView;
import com.sayohno.marketing.identity.web.dto.MembershipView;
import com.sayohno.marketing.identity.web.dto.ProfileView;
import com.sayohno.marketing.identity.web.dto.RegisterRequest;
import com.sayohno.marketing.identity.web.dto.RegisterType;
import com.sayohno.marketing.identity.web.dto.ShopView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {
    private static final char[] SHOP_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final IamUserMapper userMapper;
    private final IamMerchantMapper merchantMapper;
    private final IamMerchantMemberMapper memberMapper;
    private final IamShopMapper shopMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionStore sessionStore;
    private final IdentityContextLoader contextLoader;
    private final MerchantAccessService merchantAccessService;
    private final MarketingProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AuthService(IamUserMapper userMapper,
                       IamMerchantMapper merchantMapper,
                       IamMerchantMemberMapper memberMapper,
                       IamShopMapper shopMapper,
                       PasswordEncoder passwordEncoder,
                       SessionStore sessionStore,
                       IdentityContextLoader contextLoader,
                       MerchantAccessService merchantAccessService,
                       MarketingProperties properties) {
        this.userMapper = userMapper;
        this.merchantMapper = merchantMapper;
        this.memberMapper = memberMapper;
        this.shopMapper = shopMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.contextLoader = contextLoader;
        this.merchantAccessService = merchantAccessService;
        this.properties = properties;
    }

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        if (request.getRegisterType() == RegisterType.MERCHANT
                && (request.getMerchantName() == null || request.getMerchantName().isBlank())) {
            throw new BizException(ErrorCode.VALIDATION, "merchantName 不能为空");
        }
        IamUser user = new IamUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setStatus(IamStatus.ENABLED);
        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        Long merchantId = null;
        String storeCode = null;
        if (request.getRegisterType() == RegisterType.WORKSPACE) {
            IamMerchant workspace = new IamMerchant();
            workspace.setName(request.getUsername());
            workspace.setOwnerUserId(user.getId());
            workspace.setStatus(IamStatus.ENABLED);
            merchantMapper.insert(workspace);
            merchantId = workspace.getId();
        } else if (request.getRegisterType() == RegisterType.MERCHANT) {
            IamMerchant merchant = new IamMerchant();
            merchant.setName(request.getMerchantName().trim());
            merchant.setStatus(IamStatus.ENABLED);
            merchantMapper.insert(merchant);
            insertMember(merchant.getId(), user.getId(), UserRole.OWNER);
            String shopName = request.getShopName() == null || request.getShopName().isBlank()
                    ? request.getMerchantName().trim()
                    : request.getShopName().trim();
            storeCode = insertShop(merchant.getId(), shopName).getStoreCode();
            merchantId = merchant.getId();
        }
        return issueLogin(user, merchantId, storeCode);
    }

    public LoginResponse login(LoginRequest request) {
        IamUser user = userMapper.selectOne(Wrappers.<IamUser>lambdaQuery()
                .eq(IamUser::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_CREDENTIAL);
        }
        if (user.getStatus() == null || user.getStatus() != IamStatus.ENABLED) {
            throw new BizException(ErrorCode.DISABLED);
        }
        List<IamMerchantMember> memberships = activeMemberships(user.getId());
        IamMerchant owned = merchantMapper.selectOne(Wrappers.<IamMerchant>lambdaQuery()
                .eq(IamMerchant::getOwnerUserId, user.getId())
                .eq(IamMerchant::getStatus, IamStatus.ENABLED));
        Long merchantId = owned != null ? owned.getId() : memberships.isEmpty() ? null : memberships.get(0).getMerchantId();
        String storeCode = merchantId == null ? null : firstShopNumber(merchantId);
        return issueLogin(user, merchantId, storeCode);
    }

    public void logout(String token) {
        if (token != null) {
            sessionStore.delete(token);
        }
    }

    public ProfileView profile() {
        CurrentUser current = UserContext.require();
        return ProfileView.builder()
                .userId(String.valueOf(current.getUserId()))
                .username(current.getUsername())
                .merchantId(current.getMerchantId() == null ? null : String.valueOf(current.getMerchantId()))
                .storeCode(current.getStoreCode())
                .roles(List.copyOf(current.getRoles()))
                .build();
    }

    public LoginResponse switchMerchant(String token, Long merchantId) {
        CurrentUser current = UserContext.require();
        IamMerchantMember member = memberMapper.selectOne(Wrappers.<IamMerchantMember>lambdaQuery()
                .eq(IamMerchantMember::getUserId, current.getUserId())
                .eq(IamMerchantMember::getMerchantId, merchantId)
                .eq(IamMerchantMember::getStatus, IamStatus.ENABLED));
        IamMerchant workspace = merchantMapper.selectById(merchantId);
        boolean owner = workspace != null && Integer.valueOf(IamStatus.ENABLED).equals(workspace.getStatus())
                && current.getUserId().equals(workspace.getOwnerUserId());
        if (!owner && (member == null || !UserRole.merchantRole(member.getRole()))) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能访问这个工作空间");
        }
        IamUser user = userMapper.selectById(current.getUserId());
        return issueLogin(token, user, merchantId, firstShopNumber(merchantId));
    }

    public LoginResponse switchShop(String token, String storeCode) {
        IamShop shop = merchantAccessService.requireShop(storeCode);
        CurrentUser current = UserContext.require();
        IamUser user = userMapper.selectById(current.getUserId());
        return issueLogin(token, user, current.getMerchantId(), shop.getStoreCode());
    }

    public List<ShopView> listShops() {
        CurrentUser current = merchantAccessService.requireMerchant();
        return shopMapper.selectList(Wrappers.<IamShop>lambdaQuery()
                        .eq(IamShop::getMerchantId, current.getMerchantId())
                        .orderByAsc(IamShop::getId))
                .stream()
                .map(this::toShop)
                .toList();
    }

    public ShopView createShop(CreateShopRequest request) {
        CurrentUser current = merchantAccessService.requireOwner();
        return toShop(insertShop(current.getMerchantId(), request.getName().trim()));
    }

    public ShopView getShop(String storeCode) {
        return toShop(merchantAccessService.requireShop(storeCode));
    }

    public List<MemberView> listMembers() {
        CurrentUser current = merchantAccessService.requireMerchant();
        List<IamMerchantMember> members = memberMapper.selectList(Wrappers.<IamMerchantMember>lambdaQuery()
                .eq(IamMerchantMember::getMerchantId, current.getMerchantId())
                .orderByAsc(IamMerchantMember::getId));
        return members.stream().map(member -> {
            IamUser user = userMapper.selectById(member.getUserId());
            return MemberView.builder()
                    .userId(String.valueOf(member.getUserId()))
                    .username(user == null ? null : user.getUsername())
                    .role(member.getRole())
                    .status(member.getStatus())
                    .build();
        }).toList();
    }

    @Transactional
    public MemberView addMember(AddMemberRequest request) {
        CurrentUser current = merchantAccessService.requireOwner();
        if (!UserRole.merchantRole(request.getRole())) {
            throw new BizException(ErrorCode.VALIDATION, "角色只能是 OWNER 或 OPERATOR");
        }
        IamUser user = userMapper.selectOne(Wrappers.<IamUser>lambdaQuery()
                .eq(IamUser::getUsername, request.getUsername()));
        if (user == null || user.getStatus() == null || user.getStatus() != IamStatus.ENABLED) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        try {
            insertMember(current.getMerchantId(), user.getId(), request.getRole());
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "用户已是该商家成员");
        }
        return MemberView.builder()
                .userId(String.valueOf(user.getId()))
                .username(user.getUsername())
                .role(request.getRole())
                .status(IamStatus.ENABLED)
                .build();
    }

    private LoginResponse issueLogin(IamUser user, Long merchantId, String storeCode) {
        return issueLogin(newToken(), user, merchantId, storeCode);
    }

    private LoginResponse issueLogin(String token, IamUser user, Long merchantId, String storeCode) {
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setMerchantId(merchantId);
        session.setStoreCode(storeCode);
        session.setLoginAtEpochSeconds(java.time.Instant.now().getEpochSecond());
        sessionStore.save(token, session, properties.getSession().getTtl());
        CurrentUser current = contextLoader.load(session);
        return LoginResponse.builder()
                .token(token)
                .userId(String.valueOf(user.getId()))
                .username(user.getUsername())
                .merchantId(current.getMerchantId() == null ? null : String.valueOf(current.getMerchantId()))
                .storeCode(current.getStoreCode())
                .roles(List.copyOf(current.getRoles()))
                .memberships(membershipViews(user.getId()))
                .build();
    }

    private List<MembershipView> membershipViews(Long userId) {
        List<MembershipView> views = new java.util.ArrayList<>();
        IamMerchant owned = merchantMapper.selectOne(Wrappers.<IamMerchant>lambdaQuery()
                .eq(IamMerchant::getOwnerUserId, userId).eq(IamMerchant::getStatus, IamStatus.ENABLED));
        if (owned != null) views.add(MembershipView.builder().merchantId(String.valueOf(owned.getId()))
                .merchantName(owned.getName()).role(UserRole.OWNER).build());
        views.addAll(activeMemberships(userId).stream().map(member -> {
            IamMerchant merchant = merchantMapper.selectById(member.getMerchantId());
            return MembershipView.builder()
                    .merchantId(String.valueOf(member.getMerchantId()))
                    .merchantName(merchant == null ? null : merchant.getName())
                    .role(member.getRole())
                    .build();
        }).toList());
        return views;
    }

    private List<IamMerchantMember> activeMemberships(Long userId) {
        return memberMapper.selectList(Wrappers.<IamMerchantMember>lambdaQuery()
                .eq(IamMerchantMember::getUserId, userId)
                .eq(IamMerchantMember::getStatus, IamStatus.ENABLED)
                .orderByAsc(IamMerchantMember::getId));
    }

    private String firstShopNumber(Long merchantId) {
        IamShop shop = shopMapper.selectOne(Wrappers.<IamShop>lambdaQuery()
                .eq(IamShop::getMerchantId, merchantId)
                .eq(IamShop::getStatus, IamStatus.ENABLED)
                .orderByAsc(IamShop::getId)
                .last("limit 1"));
        return shop == null ? null : shop.getStoreCode();
    }

    private void insertMember(Long merchantId, Long userId, String role) {
        IamMerchantMember member = new IamMerchantMember();
        member.setMerchantId(merchantId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(IamStatus.ENABLED);
        memberMapper.insert(member);
    }

    private IamShop insertShop(Long merchantId, String name) {
        IamShop shop = new IamShop();
        shop.setMerchantId(merchantId);
        shop.setStoreCode(newShopNumber());
        shop.setName(name);
        shop.setStatus(IamStatus.ENABLED);
        try {
            shopMapper.insert(shop);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "店铺编号冲突，请重试");
        }
        return shop;
    }

    private ShopView toShop(IamShop shop) {
        return ShopView.builder()
                .id(String.valueOf(shop.getId()))
                .merchantId(String.valueOf(shop.getMerchantId()))
                .storeCode(shop.getStoreCode())
                .name(shop.getName())
                .status(shop.getStatus())
                .build();
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String newShopNumber() {
        char[] value = new char[12];
        for (int i = 0; i < value.length; i++) {
            value[i] = SHOP_ALPHABET[random.nextInt(SHOP_ALPHABET.length)];
        }
        return "S" + new String(value);
    }
}
