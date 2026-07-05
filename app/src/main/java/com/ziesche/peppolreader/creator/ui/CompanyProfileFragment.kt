package com.ziesche.peppolreader.creator.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.data.CompanyProfileStore
import com.ziesche.peppolreader.creator.data.PdfExporter
import com.ziesche.peppolreader.creator.model.CompanyProfile
import com.ziesche.peppolreader.databinding.FragmentCompanyProfileBinding
import java.io.File

/**
 * Edits the sender (seller) master data used to pre-fill new invoice drafts, plus the creator
 * preferences: company logo, export location for generated PDFs and the invoice-number sequence.
 * Persisted via [CompanyProfileStore]; the logo is imported immediately on pick, everything else
 * on Save.
 */
class CompanyProfileFragment : Fragment() {

    private var _binding: FragmentCompanyProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: CompanyProfileStore

    /** Path of the currently shown logo, "" when none. Persisted on Save. */
    private var logoPath: String = ""

    /** Persisted SAF tree URI for the custom export folder, "" when none. */
    private var storageTreeUri: String = ""

    private val pickLogo = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val imported = store.importLogo(uri)
        if (imported != null) {
            logoPath = imported
            renderLogo()
        } else {
            Snackbar.make(binding.root, R.string.creator_logo_error, Snackbar.LENGTH_LONG).show()
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            // Cancelled without a folder on file → fall back to Downloads.
            if (storageTreeUri.isBlank()) binding.radioStorageDownloads.isChecked = true
            return@registerForActivityResult
        }
        requireContext().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        storageTreeUri = uri.toString()
        renderStorage()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompanyProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = CompanyProfileStore(requireContext())
        bind(store.load())

        binding.btnPickLogo.setOnClickListener {
            pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnRemoveLogo.setOnClickListener {
            store.deleteLogo()
            logoPath = ""
            renderLogo()
        }

        binding.groupStorage.setOnCheckedChangeListener { _, checkedId ->
            renderStorage()
            if (checkedId == R.id.radio_storage_custom && storageTreeUri.isBlank()) {
                pickFolder.launch(null)
            }
        }
        binding.btnPickFolder.setOnClickListener { pickFolder.launch(null) }

        binding.switchAutoNumber.setOnCheckedChangeListener { _, checked ->
            binding.containerNumbering.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.switchSmallBusiness.setOnCheckedChangeListener { _, checked ->
            binding.layoutExemptionText.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked && binding.inputExemptionText.text.isNullOrBlank()) {
                binding.inputExemptionText.setText(R.string.creator_exemption_default_19)
            }
        }

        binding.btnSave.setOnClickListener {
            store.save(collect())
            Snackbar.make(binding.root, R.string.creator_saved, Snackbar.LENGTH_SHORT).show()
        }

        binding.btnLayoutEditor.setOnClickListener {
            findNavController().navigate(R.id.action_companyProfile_to_layoutEditor)
        }
    }

    private fun bind(p: CompanyProfile) = with(binding) {
        inputName.setText(p.name)
        inputStreet.setText(p.street)
        inputZip.setText(p.zip)
        inputCity.setText(p.city)
        inputCountry.setText(p.country)
        inputVatId.setText(p.vatId)
        inputTaxNumber.setText(p.taxNumber)
        inputIban.setText(p.iban)
        inputBic.setText(p.bic)
        inputEmail.setText(p.email)
        inputPhone.setText(p.phone)
        logoPath = p.logoPath.takeIf { File(it).exists() }.orEmpty()
        renderLogo()

        storageTreeUri = p.storageTreeUri
        if (p.storageMode == CompanyProfile.STORAGE_CUSTOM) {
            radioStorageCustom.isChecked = true
        } else {
            radioStorageDownloads.isChecked = true
        }
        renderStorage()

        switchAutoNumber.isChecked = p.autoNumbering
        containerNumbering.visibility = if (p.autoNumbering) View.VISIBLE else View.GONE
        inputNumberPrefix.setText(p.numberPrefix)
        inputNumberNext.setText(p.nextNumber.toString())
        inputPaymentDays.setText(if (p.defaultPaymentDays > 0) p.defaultPaymentDays.toString() else "")

        switchSmallBusiness.isChecked = p.smallBusiness
        layoutExemptionText.visibility = if (p.smallBusiness) View.VISIBLE else View.GONE
        inputExemptionText.setText(
            p.exemptionText.ifBlank { if (p.smallBusiness) getString(R.string.creator_exemption_default_19) else "" }
        )
    }

    private fun renderLogo() = with(binding) {
        val bitmap = logoPath.takeIf { it.isNotBlank() }
            ?.let { BitmapFactory.decodeFile(it) }
        if (bitmap != null) {
            imageLogoPreview.setImageBitmap(bitmap)
            imageLogoPreview.visibility = View.VISIBLE
            btnRemoveLogo.visibility = View.VISIBLE
        } else {
            imageLogoPreview.setImageDrawable(null)
            imageLogoPreview.visibility = View.GONE
            btnRemoveLogo.visibility = View.GONE
        }
    }

    private fun renderStorage() = with(binding) {
        val custom = radioStorageCustom.isChecked
        btnPickFolder.visibility = if (custom) View.VISIBLE else View.GONE
        if (custom && storageTreeUri.isNotBlank()) {
            textStorageFolder.text = getString(
                R.string.creator_storage_folder, PdfExporter.treeLabel(Uri.parse(storageTreeUri))
            )
            textStorageFolder.visibility = View.VISIBLE
        } else {
            textStorageFolder.visibility = View.GONE
        }
    }

    private fun collect(): CompanyProfile = with(binding) {
        CompanyProfile(
            name = inputName.text.str(),
            street = inputStreet.text.str(),
            zip = inputZip.text.str(),
            city = inputCity.text.str(),
            country = inputCountry.text.str().ifBlank { "DE" }.uppercase(),
            vatId = inputVatId.text.str(),
            taxNumber = inputTaxNumber.text.str(),
            iban = inputIban.text.str(),
            bic = inputBic.text.str(),
            email = inputEmail.text.str(),
            phone = inputPhone.text.str(),
            logoPath = logoPath,
            storageMode = if (radioStorageCustom.isChecked && storageTreeUri.isNotBlank())
                CompanyProfile.STORAGE_CUSTOM else CompanyProfile.STORAGE_DOWNLOADS,
            storageTreeUri = storageTreeUri,
            autoNumbering = switchAutoNumber.isChecked,
            numberPrefix = inputNumberPrefix.text.str(),
            nextNumber = inputNumberNext.text.str().toIntOrNull()?.coerceAtLeast(1) ?: 1,
            defaultPaymentDays = inputPaymentDays.text.str().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            smallBusiness = switchSmallBusiness.isChecked,
            exemptionText = inputExemptionText.text.str()
        )
    }

    private fun CharSequence?.str(): String = this?.toString()?.trim().orEmpty()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
