package com.elepay.bills;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchoolFeePaymentService {

    private final JdbcClient db;

    public SchoolFeePaymentService(JdbcClient db) {
        this.db = db;
    }

    public record StudentVerification(
            UUID id,
            String institutionCode,
            String studentId,
            String studentName,
            String programName,
            long totalFeePesewas,
            long paidFeePesewas,
            long outstandingFeePesewas,
            String academicTerm
    ) {}

    public record TuitionPaymentReceipt(
            UUID transactionId,
            String reference,
            String institutionCode,
            String studentId,
            String studentName,
            String programName,
            long amountPaidPesewas,
            long remainingOutstandingPesewas,
            String academicTerm,
            String voucherCode,
            String status
    ) {}

    public Optional<StudentVerification> verifyStudent(String institutionCode, String studentId) {
        return db.sql("""
            SELECT id, institution_code, student_id, student_name, program_name,
                   total_fee_pesewas, paid_fee_pesewas, outstanding_fee_pesewas, academic_term
            FROM student_fee_record
            WHERE institution_code = ? AND student_id = ? AND is_active = true
        """).params(institutionCode, studentId)
        .query((rs, rowNum) -> new StudentVerification(
                rs.getObject("id", UUID.class),
                rs.getString("institution_code"),
                rs.getString("student_id"),
                rs.getString("student_name"),
                rs.getString("program_name"),
                rs.getLong("total_fee_pesewas"),
                rs.getLong("paid_fee_pesewas"),
                rs.getLong("outstanding_fee_pesewas"),
                rs.getString("academic_term")
        )).optional();
    }

    @Transactional
    public TuitionPaymentReceipt payTuition(UUID customerId, String institutionCode, String studentId, long amountPesewas, String payerName) {
        StudentVerification student = verifyStudent(institutionCode, studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found for institution: " + institutionCode + ", student ID: " + studentId));

        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Tuition payment amount must be greater than zero");
        }

        if (amountPesewas > student.outstandingFeePesewas()) {
            throw new IllegalArgumentException("Payment amount (" + amountPesewas + " pesewas) exceeds outstanding fee (" + student.outstandingFeePesewas() + " pesewas)");
        }

        long newPaid = student.paidFeePesewas() + amountPesewas;
        long newOutstanding = student.outstandingFeePesewas() - amountPesewas;

        db.sql("""
            UPDATE student_fee_record
            SET paid_fee_pesewas = ?,
                outstanding_fee_pesewas = ?
            WHERE id = ?
        """).params(newPaid, newOutstanding, student.id())
        .update();

        UUID txnId = UUID.randomUUID();
        String ref = "SCH-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();
        String voucherCode = "VOUCH-" + institutionCode + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status, token_voucher)
            VALUES (?, ?, 'SCHOOL', 'SCHOOL_FEES', ?, ?, 0, ?, ?, ?, 'COMPLETED', ?)
        """).params(txnId, customerId, studentId, amountPesewas, amountPesewas, ref, voucherCode, voucherCode)
        .update();

        return new TuitionPaymentReceipt(
                txnId, ref, institutionCode, studentId, student.studentName(),
                student.programName(), amountPesewas, newOutstanding, student.academicTerm(),
                voucherCode, "COMPLETED"
        );
    }
}
