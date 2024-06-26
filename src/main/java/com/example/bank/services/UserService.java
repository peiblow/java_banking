package com.example.bank.services;

import com.example.bank.domain.user.User;
import com.example.bank.domain.user.UserType;
import com.example.bank.dtos.UserDTO;
import com.example.bank.dtos.WalletDTO;
import com.example.bank.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@Slf4j
public class UserService {
    @Autowired
    private UserRepository repository;

    @Autowired
    private WalletService walletService;

    @Autowired
    PasswordEncoder passwordEncoder;

    public void validateTransaction (User sender, BigDecimal amount, BigDecimal walletCoinBalance) throws Exception {
        if (sender.getUserType() == UserType.MERCHANT) {
            throw new Exception("Usuário do tipo LOJISTA não está autorizado a realizar transações");
        }

        if (walletCoinBalance.compareTo(amount) < 0) {
            throw new Exception("Saldo insuficiente");
        }
    }

    public User getUserById (Long id) throws RuntimeException {
        return this.repository.findUserById(id).orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
    }

    public void saveUser (User user) {
        this.repository.save(user);
    }

    public User createUser (UserDTO user) throws RuntimeException {
        try {
            User newUser = new User(user);
            newUser.setPassword(passwordEncoder.encode(newUser.getPassword()));
            this.saveUser(newUser);

            log.info("User has been created! {}", newUser.getDocument());
            log.info("User wallet creation has been started");
            WalletDTO walletDTO = new WalletDTO(newUser.getId(), BigDecimal.valueOf(0), BigDecimal.valueOf(0), BigDecimal.valueOf(0));
            walletService.createWallet(walletDTO);

            return newUser;
        } catch (RuntimeException e) {
            log.error("Erro ao criar usuario: " + e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
    }

    @Cacheable("userList")
    public List<User> getAllUsers() {
        return this.repository.findAll();
    }
}
