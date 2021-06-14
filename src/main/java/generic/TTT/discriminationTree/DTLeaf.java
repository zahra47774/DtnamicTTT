package generic.TTT.discriminationTree;

import generic.TTT.TTTNode;
import generic.modelLearning.MembershipCounter;
import moore.TTT.discriminiationTree.MooreDiscriminatorNode;
import moore.TTT.discriminiationTree.MooreDiscriminatorNode;
import net.automatalib.words.Word;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Iterator;

public class DTLeaf<I, O> extends DiscriminationNode<I, O> {
    public TTTNode<I, O> state;

    public DTLeaf(DiscriminatorNode<I, O> parent, TTTNode<I, O> node) {
        super(parent);
        this.state = node;
    }

    @Override
    public DTLeaf<I, O> sift(Word<I> sequenceAccess, MembershipCounter<I, O> membershipCounter) {
        return this;
    }

    @Override
    public boolean assertDiscriminator(
            Word<I> finalDiscriminator,
            MembershipCounter<I, O> membershipCounter,
            O expectedResult
    ) {
        return membershipCounter.membershipQuery(state.sequenceAccess.concat(finalDiscriminator)) == expectedResult;
    }

    @Override
    public @Nullable DTLeaf<I, O> find(Word<I> word) {
        if (state.sequenceAccess.equals(word)) {
            return this;
        }
        return null;
    }

    public void print(StringBuilder buffer, String prefix, String childrenPrefix) {
        buffer.append(prefix);
        buffer.append(state.id);
        buffer.append('\n');
    }
}
