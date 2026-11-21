package dev.astra.ime.oss

import com.kazumaproject.Louds.Converter
import com.kazumaproject.Louds.LOUDS
import com.kazumaproject.Louds.with_term_id.ConverterWithTermId
import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.dictionary.models.Dictionary
import com.kazumaproject.prefix.PrefixTree
import com.kazumaproject.prefix.with_term_id.PrefixTreeWithTermId
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.util.SortedMap

/**
 * Writes yomi.dat, tango.dat and token.dat with the same bytes as upstream `buildAndWriteDictionaryArtifacts`
 * (mode 0, skipKanaOnlyTango = false), using the same upstream primitives in sequential phases. Upstream builds both
 * prefix trees, then both LOUDS, and keeps all of them reachable until token.dat is written; here at most one tree
 * is reachable at a time:
 *  1. yomi: PrefixTreeWithTermId -> ConverterWithTermId -> bit sets -> write; tree and LOUDS unreachable afterwards.
 *  2. tango: PrefixTree -> Converter; the tree is unreachable before the bit sets are built. The cleared build-only
 *     lists' backing arrays are released; labels, LBS and isLeaf (used by lookups) are untouched.
 *  3. token: TokenArray.buildTokenArray against the tango LOUDS (getNodeIndex), which is then released.
 * Each tree receives its strings in upstream's iteration order (readings in key order; surfaces flattened per
 * reading), so child order, labels and term IDs are identical. Parity: DictionaryArtifactsParityTest.
 */
internal object DictionaryArtifacts {
    fun write(
        dictionaries: SortedMap<String, List<Dictionary>>,
        yomiOutputPath: String,
        tangoOutputPath: String,
        tokenOutputPath: String,
        posTableForBuildPath: String,
    ) {
        writeYomi(dictionaries, yomiOutputPath)
        val tango = tangoLouds(dictionaries)
        writeObjects(tangoOutputPath) { tango.writeExternalNotCompress(it) }
        writeObjects(tokenOutputPath) { TokenArray().buildTokenArray(dictionaries, tango, it, mode = 0, posTableForBuildPath = posTableForBuildPath) }
    }

    private fun writeYomi(dictionaries: SortedMap<String, List<Dictionary>>, path: String) {
        val yomi = yomiTrie(dictionaries).apply { convertListToBitSet() }
        writeObjects(path) { yomi.writeExternalNotCompress(it) }
    }

    private fun yomiTrie(dictionaries: SortedMap<String, List<Dictionary>>): LOUDSWithTermId {
        val tree = PrefixTreeWithTermId()
        for (reading in dictionaries.keys) tree.insert(reading)
        return ConverterWithTermId().convert(tree.root)
    }

    private fun tangoLouds(dictionaries: SortedMap<String, List<Dictionary>>): LOUDS =
        tangoTrie(dictionaries).apply {
            convertListToBitSet()
            // convertListToBitSet clears these but keeps their capacity.
            (LBSTemp as? ArrayList<*>)?.trimToSize()
            (isLeafTemp as? ArrayList<*>)?.trimToSize()
        }

    private fun tangoTrie(dictionaries: SortedMap<String, List<Dictionary>>): LOUDS {
        val tree = PrefixTree()
        for (list in dictionaries.values) for (d in list) tree.insert(d.tango)
        return Converter().convert(tree.root)
    }

    // Same stream stack as upstream; its writers close the stream themselves and print (not throw) on I/O errors,
    // so callers check the outputs.
    private inline fun writeObjects(path: String, block: (ObjectOutputStream) -> Unit) {
        ObjectOutputStream(BufferedOutputStream(FileOutputStream(path))).use(block)
    }
}
