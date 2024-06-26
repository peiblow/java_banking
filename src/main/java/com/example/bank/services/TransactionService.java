package com.example.bank.services;

import com.example.bank.domain.Wallet;
import com.example.bank.domain.transaction.Transaction;
import com.example.bank.domain.transaction.TransactionType;
import com.example.bank.domain.user.User;
import com.example.bank.dtos.TransactionDTO;
import com.example.bank.factory.WalletCoinStrategyFactory;
import com.example.bank.repositories.TransactionRepository;
import com.example.bank.strategies.wallet.ReceiverCoinStrategy;
import com.example.bank.strategies.wallet.SentCoinStrategy;
import com.example.bank.utils.aws.Bucket;
import com.example.bank.utils.aws.SQS;
import com.example.bank.utils.report.GenerateReport;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@Slf4j
public class TransactionService {
    private RedisCache redisCache;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private Bucket bucketS3;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private NotificationService notificationService;

    private final Pageable pageable = PageRequest.of(0, 5, Sort.by("timestamp").descending());

    @Cacheable("userTransaction")
    public Page<Transaction> getUserTransactions(Long id) throws Exception {
        return this.repository.findByReceiverIdOrSentId(id, pageable); // .orElseThrow(() -> new Exception("Nenhuma transação encontrada"));
    }

    @Cacheable("userTransaction")
    public Page<Transaction> getUserReceivedTransactions(Long id) throws Exception {
        return this.repository.findTransactionByReceiverId(id, pageable); // .orElseThrow(() -> new Exception("Este usuário não recebeu nenhuma transação"));
    }

    @Cacheable("userTransaction")
    public Page<Transaction> getUserSentTransactions(Long id) throws Exception {
        return this.repository.findTransactionBySentId(id, pageable); // .orElseThrow(() -> new Exception("Este usuário não efetuou nenhuma transação"));
    }

    public String circuitFallBack(Throwable throwable) {
        log.info("Informação buscada no cache");
        return Objects.requireNonNull(redisCache.get("userTransaction")).toString();
    }

    @CircuitBreaker(name = "sendNotification", fallbackMethod = "circuitFallBack")
    public Transaction createTransaction(TransactionDTO transactionDTO) throws Exception {
        Wallet sentWallet = this.walletService.findUserWallet(transactionDTO.sentId());
        Wallet receiverWallet = this.walletService.findUserWallet(transactionDTO.receiverId());

        User sent = sentWallet.getWalletOwner();
        User receiver = receiverWallet.getWalletOwner();

        boolean isAuthorized = authorizedTransaction(sent, transactionDTO.value());
        if (!isAuthorized) {
            log.error("Transação não autorizada");
            throw new Exception("Transação não autorizada");
        }

        Transaction newTransaction = new Transaction();
        newTransaction.setAmount(transactionDTO.value());
        newTransaction.setCoinType(transactionDTO.coinType());
        newTransaction.setSent(sent);
        newTransaction.setReceiver(receiver);
        newTransaction.setTimestamp(LocalDateTime.now());

        WalletCoinStrategyFactory walletStrategy = new WalletCoinStrategyFactory(
                new SentCoinStrategy(),
                new ReceiverCoinStrategy()
        );

        sentWallet = walletStrategy.getStrategy(TransactionType.SENT).pay(transactionDTO.coinType(), sentWallet, transactionDTO.value());
        receiverWallet = walletStrategy.getStrategy(TransactionType.RECEIVED).pay(transactionDTO.coinType(), receiverWallet, transactionDTO.value());

        this.repository.save(newTransaction);
        this.walletService.saveWallet(sentWallet);
        this.walletService.saveWallet(receiverWallet);

        this.notificationService.sendNotification(sent, "Transação realizada com sucesso!");
        this.notificationService.sendNotification(receiver, "Você recebeu uma nova transação!");

        return newTransaction;
    }

    public Boolean authorizedTransaction (User sender, BigDecimal value) {
    // ResponseEntity<Map> authorizationResponse = restTemplate.getForEntity("https://run.mocky.io/v3/5794d450-d2e2-4412-8131-73d0293ac1cc", Map.class);
    // String message = authorizationResponse.getBody().get("message").toString();

    // return authorizationResponse.getStatusCode() == HttpStatus.OK && message.equals("Autorizado");
        return true;
    }

    public void generateReport (Long userId, GenerateReport report) throws Exception {
        log.info("INICIANDO GERAÇÃO DE REPORT");

        try {
            Page<Transaction> reportData = this.getUserTransactions(userId);
            File generatedReport = report.generate(reportData.getContent());
            bucketS3.updateObject("my-bucket", "report" + LocalDateTime.now() + ".csv", generatedReport);
        } catch (Exception e) {
            log.error("NÃO FOI POSSIVEL GERAR O REPORT");
            throw new Exception("ERROR: " + e.getMessage());
        }
    }
}
