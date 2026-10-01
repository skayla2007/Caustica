package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData;
import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.gen.PackedPathSegmentData;
import dev.comfyfluffy.caustica.rt.gen.CachedPrimaryHitData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialLayoutTest {
    @Test
    void cachedHitFitsTheExistingSecondPathSlotAndPreservesRawPayloadWords() {
        assertEquals(48, PackedPathSegmentData.BYTE_SIZE);
        assertEquals(PackedPathSegmentData.BYTE_SIZE, CachedPrimaryHitData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(CachedPrimaryHitData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        new CachedPrimaryHitData(0x89abcdef, 2, 3, Float.floatToRawIntBits(3700.5f),
                5, 6, 7, 8, 9, 10, 11, 0).write(data);
        assertEquals(0x89abcdef, data.getInt(0));
        assertEquals(3700.5f, data.getFloat(12));
        assertEquals(7, data.getInt(24));
        assertEquals(11, data.getInt(40));
        assertEquals(0, data.getInt(44));
    }

    @Test
    void reflectedMaterialHeaderMatchesHotAbi() {
        assertEquals(80, MaterialHeaderData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(MaterialHeaderData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new MaterialHeaderData(3, 5, 7, 11,
                new Float4(0.01f, 0.02f, 0.03f, 0.04f),
                new Float4(0.05f, 0.06f, 7.0f, 8.0f),
                new Float4(0.1f, 0.2f, 1.52f, 1.0f),
                new Float4(0.3f, 0.4f, 0.5f, 0.6f)).write(data);
        assertEquals(3, data.getInt(0));
        assertEquals(5, data.getInt(4));
        assertEquals(7, data.getInt(8));
        assertEquals(11, data.getInt(12));
        assertEquals(0.01f, data.getFloat(16));
        assertEquals(7.0f, data.getFloat(40));
        assertEquals(0.1f, data.getFloat(48));
        assertEquals(1.52f, data.getFloat(56));
        assertEquals(0.6f, data.getFloat(76));
    }

    @Test
    void reflectedWorldPushConstantsIncludeLightBuffersAndFrameIndex() {
        // Ten uint64_t addresses followed by the frame index and dynamic-scene gate.
        assertEquals(88, WorldPushConstantsData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(WorldPushConstantsData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new WorldPushConstantsData(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11, 1).write(data);
        assertEquals(4L, data.getLong(24));  // materialTableAddr
        assertEquals(5L, data.getLong(32));  // lightBufAddr
        assertEquals(9L, data.getLong(64));  // lightGridSpanAddr (last of the light-buffer addresses)
        assertEquals(10L, data.getLong(72)); // pathQueueAddr
        assertEquals(11, data.getInt(80));   // frameIndex
        assertEquals(1, data.getInt(84));    // separate dynamic scene
    }
}
