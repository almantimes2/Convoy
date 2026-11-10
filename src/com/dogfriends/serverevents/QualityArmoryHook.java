package com.dogfriends.serverevents;

import me.zombie_striker.qg.api.QualityArmory;
import org.bukkit.inventory.ItemStack;

// Acceso a la API de QualityArmory aislado en su propia clase: Java solo la carga si
// de verdad se llama, asi el plugin no revienta cuando QualityArmory no esta instalado
// (es softdepend). NUNCA llamar a estos metodos sin comprobar antes que el plugin
// QualityArmory esta presente.
final class QualityArmoryHook {

    private QualityArmoryHook() {
    }

    // true si el nombre corresponde a un item registrado (campo "name:" interno de los
    // yml de QualityArmory, ej: ak47, glock, 9mm, shell - NO el nombre de archivo).
    static boolean itemExists(String name) {
        return QualityArmory.getCustomItemByName(name) != null;
    }

    static ItemStack getItem(String name) {
        return QualityArmory.getCustomItemAsItemStack(name);
    }
}
