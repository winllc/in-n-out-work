{
	"subject": {

                       "extraNames": [
                       {
                             "type": "0.9.2342.19200300.100.1.25",
                             "value": "com"
                         },
                          {
                          "type": "0.9.2342.19200300.100.1.25",
                          "value": "winllc"
                      },
                       {
                                "type": "2.5.4.11",
                                "value": "Users"
                            },
                        {
                                 "type": "2.5.4.3",
                                 "value": {{ toJson .Subject.CommonName }}
                             }

                       ]
                   },
	"sans": {{ toJson .SANs }},
{{- if typeIs "*rsa.PublicKey" .Insecure.CR.PublicKey }}
	"keyUsage": ["keyEncipherment", "digitalSignature"],
{{- else }}
	"keyUsage": ["digitalSignature"],
{{- end }}
	"extKeyUsage": ["serverAuth", "clientAuth"]
}