import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.util.RandomSource;

public class TestGrad {
    public static void main(String[] args) {
        RandomSource rand = RandomSource.create(42);
        ImprovedNoise noise = new ImprovedNoise(rand);
        // We'll just generate the terrain natively to see what it uses.
        System.out.println("TestGrad compiled.");
    }
}
