package com.shkeiru.gpugen;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(GpuWorldGenMod.MODID)
public class GpuWorldGenMod {

    public static final String MODID = "gpugen";

    public GpuWorldGenMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        
        // Nettoyage: L'ancien Registre Defered de Codec pour notre propre Générateur GPU a été annihilé.
        // À la place, une mécanique purement 'Zero-Config' écrase furtivement le Generator Vanilla via Mixin.
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Init logic facultative à venir
    }
}
