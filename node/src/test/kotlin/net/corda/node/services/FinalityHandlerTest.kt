package net.corda.node.services

import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.StateMachineRunId
import net.corda.core.internal.cordapp.CordappInfoResolver
import net.corda.core.internal.packageName
import net.corda.core.toFuture
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.issuedBy
import net.corda.node.services.statemachine.StaffedFlowHospital
import net.corda.node.services.statemachine.StaffedFlowHospital.MedicalRecord.FinalityObservation
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test

class FinalityHandlerTest {
    private val mockNet = InternalMockNetwork()

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `sent to flow hospital on error and attempted retry on node restart`() {
        // Setup a network where only Alice has the finance CorDapp and it sends a cash tx to Bob who doesn't have the
        // CorDapp. Bob's FinalityHandler will error when validating the tx.
        val alice = mockNet.createNode(InternalMockNodeParameters(
                legalName = ALICE_NAME,
                additionalCordapps = setOf(FINANCE_CORDAPP)
        ))

        var bob = mockNet.createNode(InternalMockNodeParameters(
                legalName = BOB_NAME,
                // The node disables the FinalityHandler completely if there are no old CorDapps loaded, so we need to add
                // a token old CorDapp to keep the handler running.
                additionalCordapps = setOf(cordappForPackages(javaClass.packageName).withTargetVersion(3))
        ))

        val stx = alice.issuesCashTo(bob)
        val finalityHandlerId = bob.trackFinalityHandlerId().run {
            alice.finaliseWithOldApi(stx)
            getOrThrow()
        }

        bob.assertFlowSentForObservationWithConstraintError(finalityHandlerId, stx.id)
        assertThat(bob.getTransaction(stx.id)).isNull()

        bob = mockNet.restartNode(bob)
        // Since we've not done anything to fix the orignal error, we expect the finality handler to be sent to the hospital
        // again on restart
        bob.assertFlowSentForObservationWithConstraintError(finalityHandlerId, stx.id)
        assertThat(bob.getTransaction(stx.id)).isNull()
    }

    @Test
    fun `disabled if there are no old CorDapps loaded`() {
        val alice = mockNet.createNode(InternalMockNodeParameters(
                legalName = ALICE_NAME,
                additionalCordapps = setOf(FINANCE_CORDAPP)
        ))

        val bob = mockNet.createNode(InternalMockNodeParameters(
                legalName = BOB_NAME,
                // Make sure the target version is 4, and not the current platform version which may be greater
                additionalCordapps = setOf(FINANCE_CORDAPP.withTargetVersion(4))
        ))

        val stx = alice.issuesCashTo(bob)
        val finalityHandlerId = bob.trackFinalityHandlerId().run {
            alice.finaliseWithOldApi(stx)
            getOrThrow()
        }

        val error = bob.assertFlowSentForObservationWithError(finalityHandlerId, stx.id)
        assertThat(error).hasMessageContaining("${alice.info.singleIdentity()} is attempting to use the old insecure API of FinalityFlow")
        assertThat(bob.getTransaction(stx.id)).isNull()
    }

    private fun TestStartedNode.issuesCashTo(recipient: TestStartedNode): SignedTransaction {
        return TransactionBuilder(mockNet.defaultNotaryIdentity).let {
            Cash().generateIssue(
                    it,
                    1000.POUNDS.issuedBy(info.singleIdentity().ref(0)),
                    recipient.info.singleIdentity(),
                    mockNet.defaultNotaryIdentity
            )
            services.signInitialTransaction(it)
        }
    }

    private fun TestStartedNode.trackFinalityHandlerId(): CordaFuture<StateMachineRunId> {
        return smm
                .track()
                .updates
                .filter { it.logic is FinalityHandler }
                .map { it.logic.runId }
                .toFuture()
    }

    private fun TestStartedNode.finaliseWithOldApi(stx: SignedTransaction) {
        CordappInfoResolver.withCordappInfo(targetPlatformVersion = 3) {
            @Suppress("DEPRECATION")
            services.startFlow(FinalityFlow(stx)).resultFuture.apply {
                mockNet.runNetwork()
            }
        }
    }

    private fun TestStartedNode.assertFlowSentForObservationWithError(runId: StateMachineRunId, stxId: SecureHash): Throwable {
        val finalityObservation = smm
                .flowHospital
                .track()
                .let { it.updates.startWith(it.snapshot) }
                .filter { it.flowId == runId }
                .ofType(FinalityObservation::class.java)
                .toBlocking()
                .first()
        assertThat(finalityObservation.by).contains(StaffedFlowHospital.FinalityDoctor)
        assertThat(finalityObservation.stx?.id).isEqualTo(stxId)
        return finalityObservation.errors.single()
    }

    private fun TestStartedNode.assertFlowSentForObservationWithConstraintError(runId: StateMachineRunId, stxId: SecureHash) {
        val error = assertFlowSentForObservationWithError(runId, stxId)
        assertThat(error).isInstanceOf(TransactionVerificationException.ContractConstraintRejection::class.java)
    }

    private fun TestStartedNode.getTransaction(id: SecureHash): SignedTransaction? {
        return services.validatedTransactions.getTransaction(id)
    }
}
