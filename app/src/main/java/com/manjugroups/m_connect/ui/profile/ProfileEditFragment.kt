package com.manjugroups.m_connect.ui.profile

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.EmergencyContact
import com.manjugroups.m_connect.network.StaffFullData
import com.manjugroups.m_connect.network.UpdateMyProfileRequest
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch

/**
 * Personal + family details edit form. Backed by /api/staff/me/update which
 * whitelists self-editable fields and forces id = auth.user._id.
 */
class ProfileEditFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile_edit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<View>(R.id.btnEditBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        labelField(view, R.id.fldName, "Full Name", "Your full name")
        labelField(view, R.id.fldDob, "Date of Birth (YYYY-MM-DD)", "1990-01-01")
        labelField(view, R.id.fldGender, "Gender (male / female / other)", "male")
        labelField(view, R.id.fldMaritalStatus, "Marital Status (single / married / divorced / widowed)", "single")
        labelField(view, R.id.fldBloodGroup, "Blood Group", "O+")
        labelField(view, R.id.fldReligion, "Religion", "")
        labelField(view, R.id.fldNationality, "Nationality", "Indian")
        labelField(view, R.id.fldAddress, "Address", "Street, area")
        labelField(view, R.id.fldCity, "City", "")
        labelField(view, R.id.fldState, "State", "")
        labelField(view, R.id.fldPincode, "Pincode", "")
        labelField(view, R.id.fldFatherName, "Father's Name", "")
        labelField(view, R.id.fldMotherName, "Mother's Name", "")
        labelField(view, R.id.fldSpouseName, "Spouse Name (if married)", "")
        labelField(view, R.id.fldAnniversary, "Anniversary (YYYY-MM-DD)", "")
        labelField(view, R.id.fldEmergencyName, "Contact Name", "")
        labelField(view, R.id.fldEmergencyPhone, "Contact Phone", "")
        labelField(view, R.id.fldEmergencyRelation, "Relation", "Father / Spouse / …")

        view.findViewById<View>(R.id.btnSaveProfile).setOnClickListener { onSave(view) }

        loadStaff(view)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    private fun loadStaff(root: View) {
        val loading = root.findViewById<View>(R.id.editLoading)
        val scroll = root.findViewById<View>(R.id.editScroll)
        loading.visibility = View.VISIBLE
        scroll.visibility = View.GONE

        val id = session.staffId?.takeIf { it.isNotBlank() }
        if (id == null) {
            Toast.makeText(requireContext(), "Missing session", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaffDetail(session.bearerToken, id)
                val staff = resp.staff
                if (!resp.success || staff == null) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to load profile",
                        Toast.LENGTH_LONG
                    ).show()
                    parentFragmentManager.popBackStack()
                    return@launch
                }
                fillForm(root, staff)
                loading.visibility = View.GONE
                scroll.visibility = View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Network error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                parentFragmentManager.popBackStack()
            } finally {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
            }
        }
    }

    private fun fillForm(root: View, staff: StaffFullData) {
        setFieldValue(root, R.id.fldName, staff.name)
        setFieldValue(root, R.id.fldDob, staff.dateOfBirth)
        setFieldValue(root, R.id.fldGender, staff.gender)
        setFieldValue(root, R.id.fldMaritalStatus, staff.maritalStatus)
        setFieldValue(root, R.id.fldBloodGroup, staff.bloodGroup)
        setFieldValue(root, R.id.fldReligion, staff.religion)
        setFieldValue(root, R.id.fldNationality, staff.nationality)
        setFieldValue(root, R.id.fldAddress, staff.address)
        setFieldValue(root, R.id.fldCity, staff.city)
        setFieldValue(root, R.id.fldState, staff.state)
        setFieldValue(root, R.id.fldPincode, staff.pincode)
        setFieldValue(root, R.id.fldFatherName, staff.fatherName)
        setFieldValue(root, R.id.fldMotherName, staff.motherName)
        // Spouse + anniversary aren't on StaffFullData; leave blank, server returns
        // them on subsequent save.
        setFieldValue(root, R.id.fldEmergencyName, staff.emergencyContact?.name)
        setFieldValue(root, R.id.fldEmergencyPhone, staff.emergencyContact?.phone)
        setFieldValue(root, R.id.fldEmergencyRelation, staff.emergencyContact?.relation)
    }

    private fun onSave(root: View) {
        val errorView = root.findViewById<TextView>(R.id.tvEditError)
        errorView.visibility = View.GONE
        val saveBtn = root.findViewById<View>(R.id.btnSaveProfile)
        saveBtn.isEnabled = false

        val ec = run {
            val name = readField(root, R.id.fldEmergencyName)
            val phone = readField(root, R.id.fldEmergencyPhone)
            val relation = readField(root, R.id.fldEmergencyRelation)
            if (!name.isNullOrBlank() && !phone.isNullOrBlank() && !relation.isNullOrBlank()) {
                EmergencyContact(name = name, phone = phone, relation = relation)
            } else null
        }

        val req = UpdateMyProfileRequest(
            name = readField(root, R.id.fldName),
            dateOfBirth = readField(root, R.id.fldDob),
            gender = readField(root, R.id.fldGender)?.lowercase(),
            maritalStatus = readField(root, R.id.fldMaritalStatus)?.lowercase(),
            spouseName = readField(root, R.id.fldSpouseName),
            fatherName = readField(root, R.id.fldFatherName),
            motherName = readField(root, R.id.fldMotherName),
            religion = readField(root, R.id.fldReligion),
            nationality = readField(root, R.id.fldNationality),
            bloodGroup = readField(root, R.id.fldBloodGroup),
            address = readField(root, R.id.fldAddress),
            city = readField(root, R.id.fldCity),
            state = readField(root, R.id.fldState),
            pincode = readField(root, R.id.fldPincode),
            anniversary = readField(root, R.id.fldAnniversary),
            emergencyContact = ec,
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.updateMyProfile(session.bearerToken, req)
                saveBtn.isEnabled = true
                if (resp.success) {
                    Toast.makeText(requireContext(), "Profile updated", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    errorView.text = resp.error ?: "Failed to update"
                    errorView.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                saveBtn.isEnabled = true
                errorView.text = "Network error: ${e.message ?: "unknown"}"
                errorView.visibility = View.VISIBLE
            }
        }
    }

    // ── tiny include-layout helpers ─────────────────────────────────────────

    private fun labelField(root: View, includeId: Int, label: String, hint: String) {
        val container = root.findViewById<View>(includeId) ?: return
        container.findViewById<TextView>(R.id.tvFieldLabel)?.text = label
        container.findViewById<EditText>(R.id.etFieldValue)?.hint = hint
    }

    private fun setFieldValue(root: View, includeId: Int, value: String?) {
        val container = root.findViewById<View>(includeId) ?: return
        container.findViewById<EditText>(R.id.etFieldValue)?.setText(value.orEmpty())
    }

    private fun readField(root: View, includeId: Int): String? {
        val container = root.findViewById<View>(includeId) ?: return null
        val text = container.findViewById<EditText>(R.id.etFieldValue)?.text?.toString()?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }
}
