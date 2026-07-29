/* =================================================================
   MOSIP Collab — self registration flow
   -----------------------------------------------------------------
   One script for all three steps. Form data lives in sessionStorage
   so Preview / Confirm / Success work on what the user actually typed.
   ================================================================= */
(function () {
  'use strict';

  var FORM_KEY = 'mosip.registration';
  var RESULT_KEY = 'mosip.result';

  /* ------------------------------- storage ------------------------------- */

  function readStore(key) {
    try {
      return JSON.parse(sessionStorage.getItem(key)) || null;
    } catch (err) {
      return null;
    }
  }

  function writeStore(key, value) {
    try {
      sessionStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch (err) {
      // Quota is the only realistic failure here, and only the photo is big.
      return false;
    }
  }

  function clearStore(key) {
    try {
      sessionStorage.removeItem(key);
    } catch (err) {
      /* nothing useful to do */
    }
  }

  /* ------------------------------ formatting ----------------------------- */

  // "01-09-1984" (what the field holds) -> "01/09/1984" (what Confirm shows)
  function displayDate(value) {
    return value ? value.replace(/-/g, '/') : '';
  }

  function titleCase(value) {
    if (!value) return '';
    return value.charAt(0).toUpperCase() + value.slice(1);
  }

  function parseDob(value) {
    var match = /^(\d{2})-(\d{2})-(\d{4})$/.exec(value || '');
    if (!match) return null;

    var day = Number(match[1]);
    var month = Number(match[2]);
    var year = Number(match[3]);
    var date = new Date(year, month - 1, day);

    // Rejects impossible dates such as 31-02-1990, which Date() rolls over.
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
      return null;
    }
    return date;
  }

  /* =================================================================
     Step 1 — the registration form
     ================================================================= */

  function initForm(form) {
    var photoData = null;

    var els = {
      fullName: form.querySelector('#fullName'),
      dob: form.querySelector('#dob'),
      dobPicker: form.querySelector('#dobPicker'),
      dobButton: form.querySelector('#dobButton'),
      gender: form.querySelector('#gender'),
      email: form.querySelector('#email'),
      mobile: form.querySelector('#mobile'),
      address: form.querySelector('#address'),
      consent: form.querySelector('#consent'),
      photoImage: form.querySelector('#photoImage'),
      photoBox: form.querySelector('#photoPreview'),
      photoFile: form.querySelector('#photoFile'),
      takePhoto: form.querySelector('#takePhotoBtn'),
      removePhoto: form.querySelector('#removePhotoBtn'),
      clear: form.querySelector('#clearFormBtn')
    };

    var placeholderSrc = els.photoImage.getAttribute('src');

    /* ---------------------------- error helpers ---------------------------- */

    function errorNodeFor(field) {
      return form.querySelector('#' + field + 'Error');
    }

    function setError(field, message) {
      var node = errorNodeFor(field);
      var input = els[field];

      if (node) node.textContent = message || '';

      if (input) {
        input.classList.toggle('input--error', Boolean(message));
        input.setAttribute('aria-invalid', message ? 'true' : 'false');
      }
      if (field === 'photo') {
        els.photoBox.classList.toggle('photo--error', Boolean(message));
      }
      if (field === 'consent') {
        els.consent.classList.toggle('checkbox--error', Boolean(message));
      }
    }

    function clearAllErrors() {
      ['fullName', 'dob', 'photo', 'gender', 'email', 'mobile', 'address', 'consent']
        .forEach(function (field) { setError(field, ''); });
    }

    /* ------------------------------ validation ----------------------------- */

    var validators = {
      fullName: function () {
        var value = els.fullName.value.trim();
        if (!value) return 'Full Name is required.';
        if (value.length < 2) return 'Please enter your full name.';
        if (!/^[\p{L}][\p{L}\s.'-]*$/u.test(value)) {
          return 'Use letters, spaces, hyphens and apostrophes only.';
        }
        return '';
      },

      dob: function () {
        var value = els.dob.value.trim();
        if (!value) return 'Date of Birth is required.';

        var date = parseDob(value);
        if (!date) return 'Enter a valid date as DD-MM-YYYY.';

        var today = new Date();
        today.setHours(0, 0, 0, 0);
        if (date > today) return 'Date of Birth cannot be in the future.';
        if (date.getFullYear() < 1900) return 'Enter a year of 1900 or later.';
        return '';
      },

      photo: function () {
        return photoData ? '' : 'A photo is required.';
      },

      email: function () {
        var value = els.email.value.trim();
        if (!value) return 'Email ID is required.';
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(value)) return 'Enter a valid email address.';
        return '';
      },

      mobile: function () {
        var value = els.mobile.value.trim();
        if (!value) return ''; // optional in the design
        if (!/^[+]?[\d\s-]{7,18}$/.test(value)) return 'Enter a valid mobile number.';
        return '';
      },

      consent: function () {
        return els.consent.checked ? '' : 'You must consent before registering.';
      }
    };

    function validateField(field) {
      var message = validators[field] ? validators[field]() : '';
      setError(field, message);
      return !message;
    }

    function validateAll() {
      // Validate every field first so the user sees all problems at once.
      var fields = ['fullName', 'dob', 'photo', 'email', 'mobile', 'consent'];
      var firstBad = null;

      fields.forEach(function (field) {
        if (!validateField(field) && !firstBad) firstBad = field;
      });

      if (firstBad) {
        var target = firstBad === 'photo' ? els.takePhoto : els[firstBad];
        if (target && target.focus) target.focus();
        if (target && target.scrollIntoView) {
          target.scrollIntoView({ block: 'center', behavior: 'smooth' });
        }
      }
      return !firstBad;
    }

    // Re-validate a field once it has been touched, but only to clear errors
    // as the user fixes them — never to nag mid-typing.
    ['fullName', 'dob', 'email', 'mobile'].forEach(function (field) {
      els[field].addEventListener('blur', function () { validateField(field); });
      els[field].addEventListener('input', function () {
        if (errorNodeFor(field).textContent) validateField(field);
      });
    });

    els.consent.addEventListener('change', function () { validateField('consent'); });

    /* ------------------------- date of birth helpers ------------------------ */

    els.dob.addEventListener('input', function () {
      var digits = els.dob.value.replace(/\D/g, '').slice(0, 8);
      var parts = [digits.slice(0, 2), digits.slice(2, 4), digits.slice(4, 8)];
      els.dob.value = parts.filter(Boolean).join('-');
    });

    if (els.dobButton && els.dobPicker) {
      els.dobButton.addEventListener('click', function () {
        try {
          if (typeof els.dobPicker.showPicker === 'function') {
            els.dobPicker.showPicker();
          } else {
            els.dob.focus();
          }
        } catch (err) {
          els.dob.focus();
        }
      });

      els.dobPicker.addEventListener('change', function () {
        var value = els.dobPicker.value; // YYYY-MM-DD
        if (!value) return;
        var bits = value.split('-');
        els.dob.value = bits[2] + '-' + bits[1] + '-' + bits[0];
        validateField('dob');
      });
    }

    /* --------------------------------- photo -------------------------------- */

    function setPhoto(dataUrl) {
      photoData = dataUrl;

      if (dataUrl) {
        els.photoImage.setAttribute('src', dataUrl);
        els.photoImage.setAttribute('alt', 'Your uploaded photo');
        els.photoBox.classList.add('photo--filled');
        els.removePhoto.hidden = false;
        setError('photo', '');
      } else {
        els.photoImage.setAttribute('src', placeholderSrc);
        els.photoImage.setAttribute('alt', '');
        els.photoBox.classList.remove('photo--filled');
        els.removePhoto.hidden = true;
        els.photoFile.value = '';
      }
    }

    // Downscale before storing: a full-size camera frame will not fit in
    // sessionStorage, and the confirm page only renders it small anyway.
    function fileToDataUrl(file, done) {
      var reader = new FileReader();

      reader.onload = function () {
        var img = new Image();
        img.onload = function () { done(shrink(img)); };
        img.onerror = function () { done(null); };
        img.src = reader.result;
      };
      reader.onerror = function () { done(null); };
      reader.readAsDataURL(file);
    }

    function shrink(source) {
      var maxSide = 720;
      var width = source.naturalWidth || source.videoWidth || source.width;
      var height = source.naturalHeight || source.videoHeight || source.height;
      var scale = Math.min(1, maxSide / Math.max(width, height));

      var canvas = document.createElement('canvas');
      canvas.width = Math.round(width * scale);
      canvas.height = Math.round(height * scale);
      canvas.getContext('2d').drawImage(source, 0, 0, canvas.width, canvas.height);

      return canvas.toDataURL('image/jpeg', 0.85);
    }

    els.photoFile.addEventListener('change', function () {
      var file = els.photoFile.files && els.photoFile.files[0];
      if (!file) return;

      if (!/^image\//.test(file.type)) {
        setError('photo', 'Choose an image file.');
        els.photoFile.value = '';
        return;
      }
      if (file.size > 10 * 1024 * 1024) {
        setError('photo', 'That image is over 10 MB. Choose a smaller one.');
        els.photoFile.value = '';
        return;
      }

      fileToDataUrl(file, function (dataUrl) {
        if (dataUrl) {
          setPhoto(dataUrl);
        } else {
          setError('photo', 'That image could not be read. Try another.');
        }
      });
    });

    els.removePhoto.addEventListener('click', function () {
      setPhoto(null);
      els.takePhoto.focus();
    });

    els.photoBox.addEventListener('click', function () {
      if (!photoData) els.takePhoto.click();
    });

    initCamera(els, setPhoto, shrink, setError);

    /* -------------------------------- actions ------------------------------- */

    els.clear.addEventListener('click', function () {
      form.reset();
      setPhoto(null);
      clearAllErrors();
      clearStore(FORM_KEY);
      clearStore(RESULT_KEY);
      els.fullName.focus();
    });

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      if (!validateAll()) return;

      var payload = {
        fullName: els.fullName.value.trim(),
        dob: els.dob.value.trim(),
        gender: els.gender.value,
        email: els.email.value.trim(),
        mobile: els.mobile.value.trim(),
        address: els.address.value.trim(),
        photo: photoData
      };

      if (!writeStore(FORM_KEY, payload)) {
        setError('photo', 'That photo is too large to carry over. Please use a smaller image.');
        return;
      }
      window.location.href = 'confirm.html';
    });

    /* ------------------------- restore a previous entry ---------------------- */

    var saved = readStore(FORM_KEY);
    if (saved) {
      els.fullName.value = saved.fullName || '';
      els.dob.value = saved.dob || '';
      els.gender.value = saved.gender || '';
      els.email.value = saved.email || '';
      els.mobile.value = saved.mobile || '';
      els.address.value = saved.address || '';
      if (saved.photo) setPhoto(saved.photo);
      els.consent.checked = true;
    }
  }

  /* ------------------------------ camera modal ----------------------------- */

  function initCamera(els, setPhoto, shrink, setError) {
    var modal = document.getElementById('cameraModal');

    // Without the dialog there is still a working path: the file picker.
    if (!modal) {
      els.takePhoto.addEventListener('click', function () { els.photoFile.click(); });
      return;
    }

    var video = modal.querySelector('#cameraVideo');
    var capture = modal.querySelector('#captureBtn');
    var message = modal.querySelector('#cameraError');
    var stream = null;

    function supported() {
      return Boolean(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
    }

    function close() {
      if (stream) {
        stream.getTracks().forEach(function (track) { track.stop(); });
        stream = null;
      }
      video.srcObject = null;
      modal.hidden = true;
      document.body.classList.remove('is-locked');
      els.takePhoto.focus();
    }

    function open() {
      message.textContent = '';
      modal.hidden = false;
      document.body.classList.add('is-locked');

      navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user' }, audio: false })
        .then(function (media) {
          stream = media;
          video.srcObject = media;
          capture.disabled = false;
        })
        .catch(function () {
          // Denied, no camera, or a non-secure context (file:// pages).
          capture.disabled = true;
          message.textContent =
            'The camera is not available here. Use “Upload instead” to pick a photo.';
        });
    }

    els.takePhoto.addEventListener('click', function () {
      if (supported()) {
        open();
      } else {
        els.photoFile.click();
      }
    });

    capture.addEventListener('click', function () {
      if (!stream) return;
      setPhoto(shrink(video));
      close();
    });

    modal.addEventListener('click', function (event) {
      if (event.target.hasAttribute('data-close')) close();
      if (event.target.id === 'uploadInsteadBtn') {
        close();
        els.photoFile.click();
      }
    });

    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !modal.hidden) close();
    });
  }

  /* =================================================================
     Step 2 — Confirm Details
     ================================================================= */

  function initConfirm(root) {
    var data = readStore(FORM_KEY);

    if (!data) {
      window.location.replace('index.html');
      return;
    }

    var fields = {
      fullName: data.fullName,
      dob: displayDate(data.dob),
      gender: titleCase(data.gender) || '—',
      email: data.email,
      mobile: data.mobile || '—',
      address: data.address || '—'
    };

    Object.keys(fields).forEach(function (key) {
      var node = root.querySelector('[data-value="' + key + '"]');
      if (node) node.textContent = fields[key];
    });

    var photo = root.querySelector('[data-value="photo"]');
    if (photo && data.photo) {
      photo.setAttribute('src', data.photo);
      photo.setAttribute('alt', 'Photo of ' + data.fullName);
    }

    var register = root.querySelector('#registerBtn');
    if (register) {
      register.addEventListener('click', function (event) {
        event.preventDefault();

        writeStore(RESULT_KEY, {
          uin: generateUin(),
          fullName: data.fullName,
          email: data.email
        });
        window.location.href = 'success.html';
      });
    }
  }

  // Stand-in for the number the ID system would issue.
  function generateUin() {
    var digits = '';
    for (var i = 0; i < 10; i++) {
      digits += Math.floor(Math.random() * 10);
    }
    return digits;
  }

  /* =================================================================
     Step 3 — Success
     ================================================================= */

  function initSuccess(root) {
    var result = readStore(RESULT_KEY);

    if (!result) {
      window.location.replace('index.html');
      return;
    }

    var uin = root.querySelector('[data-value="uin"]');
    if (uin) uin.textContent = result.uin;

    var email = root.querySelector('[data-value="email"]');
    if (email && result.email) email.textContent = result.email;

    // The registration is done — a fresh visit to the form should start empty.
    clearStore(FORM_KEY);
  }

  /* -------------------------------- bootstrap ------------------------------- */

  document.addEventListener('DOMContentLoaded', function () {
    var form = document.getElementById('registrationForm');
    if (form) return initForm(form);

    var confirmCard = document.getElementById('confirmCard');
    if (confirmCard) return initConfirm(confirmCard);

    var successCard = document.getElementById('successCard');
    if (successCard) return initSuccess(successCard);
  });
})();
