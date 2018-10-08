package net.corda.node.services

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.UpgradedContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.ContractUpgradeUtils
import net.corda.core.transactions.ContractUpgradeWireTransaction
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction

/**
 * The FinalityHandler is insecure as it blindly accepts any and all transactions into the node's local vault without
 * doing any checks. To plug this hole, the sending-side FinalityFlow is gated to only work with old CorDapps (those whose
 * target platform version < 4), and this flow will only work if there are old CorDapps loaded (to preserve backwards
 * compatibility).
 *
 * If an attempt is made to send us a transaction via FinalityHandler, and it's disabled, then FinalityHandler will receive
 * it as usual using ReceiveTransactionFlow (!) but then subsequently throw a FlowException. The throwing of the exception
 * will rollback the transaction from the database so that it's not commited and calling ReceiveTransactionFlow gives us
 * the guarantee that the transaction itself is valid to manually record via the flow hospital.
 */
// TODO Should we worry about (accidental?) spamming of the flow hospital from other members using the old API
class FinalityHandler(val sender: FlowSession, private val disable: Boolean) : FlowLogic<Unit>() {
    val receiveSubFlow = ReceiveTransactionFlow(sender, true, StatesToRecord.ONLY_RELEVANT)

    @Suspendable
    override fun call() {
        subFlow(receiveSubFlow)
        if (disable) {
            throw FlowException("${sender.counterparty} is attempting to use the old insecure API of FinalityFlow. " +
                    "This API however is disabled on this node since there no CorDapps installed that require it. " +
                    "It may be that ${sender.counterparty} is running an older verison of a CorDapp to us. In the meantime " +
                    "the transaction can be recovered from the flow hospital.")
            // TODO Add a flow hospital API so that the above statement is possible!
        }
    }
}

class NotaryChangeHandler(otherSideSession: FlowSession) : AbstractStateReplacementFlow.Acceptor<Party>(otherSideSession) {
    /**
     * Check the notary change proposal.
     *
     * For example, if the proposed new notary has the same behaviour (e.g. both are non-validating)
     * and is also in a geographically convenient location we can just automatically approve the change.
     * TODO: In more difficult cases this should call for human attention to manually verify and approve the proposal
     */
    override fun verifyProposal(stx: SignedTransaction, proposal: AbstractStateReplacementFlow.Proposal<Party>) {
        val state = proposal.stateRef
        val proposedTx = stx.resolveNotaryChangeTransaction(serviceHub)
        val newNotary = proposal.modification

        if (state !in proposedTx.inputs.map { it.ref }) {
            throw StateReplacementException("The proposed state $state is not in the proposed transaction inputs")
        }

        // TODO: load and compare against notary whitelist from config. Remove the check below
        val isNotary = serviceHub.networkMapCache.isNotary(newNotary)
        if (!isNotary) {
            throw StateReplacementException("The proposed node $newNotary does not run a Notary service")
        }
    }
}

class ContractUpgradeHandler(otherSide: FlowSession) : AbstractStateReplacementFlow.Acceptor<Class<out UpgradedContract<ContractState, *>>>(otherSide) {
    @Suspendable
    override fun verifyProposal(stx: SignedTransaction, proposal: AbstractStateReplacementFlow.Proposal<Class<out UpgradedContract<ContractState, *>>>) {
        // Retrieve signed transaction from our side, we will apply the upgrade logic to the transaction on our side, and
        // verify outputs matches the proposed upgrade.
        val ourSTX = serviceHub.validatedTransactions.getTransaction(proposal.stateRef.txhash)
        requireNotNull(ourSTX) { "We don't have a copy of the referenced state" }
        val oldStateAndRef = ourSTX!!.resolveBaseTransaction(serviceHub).outRef<ContractState>(proposal.stateRef.index)
        val authorisedUpgrade = serviceHub.contractUpgradeService.getAuthorisedContractUpgrade(oldStateAndRef.ref) ?: throw IllegalStateException("Contract state upgrade is unauthorised. State hash : ${oldStateAndRef.ref}")
        val proposedTx = stx.coreTransaction as ContractUpgradeWireTransaction
        val expectedTx = ContractUpgradeUtils.assembleUpgradeTx(oldStateAndRef, proposal.modification, proposedTx.privacySalt, serviceHub)
        requireThat {
            "The instigator is one of the participants" using (initiatingSession.counterparty in oldStateAndRef.state.data.participants)
            "The proposed upgrade ${proposal.modification.javaClass} is a trusted upgrade path" using (proposal.modification.name == authorisedUpgrade)
            "The proposed tx matches the expected tx for this upgrade" using (proposedTx == expectedTx)
        }
        proposedTx.resolve(serviceHub, stx.sigs)
    }
}
