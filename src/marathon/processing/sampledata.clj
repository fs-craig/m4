(ns marathon.processing.sampledata)

(def cases 
	[{:CaseName "Case1",
	  :Enabled "TRUE",
	  :Futures 5,
	  :MaxDuration 5000,
	  :RandomSeed 5,
	  :Tfinal 5000,
	  :Replacement "TRUE"}
	 {:CaseName "Case2",
	  :Enabled "FALSE",
	  :Futures 3,
	  :MaxDuration 5000,
	  :RandomSeed 2468,
	  :Tfinal 5000,
	  :Replacement "TRUE"}
	 {:CaseName "Case3",
	  :Enabled "FALSE",
	  :Futures 1,
	  :MaxDuration 5000,
	  :RandomSeed 2,
	  :Tfinal 5000,
	  :Replacement "TRUE"}])
  
;a set of notional rules...
(def rule-records 
  [{:S3 "nil",
  :Frequency 2,
  :StartDistribution "uniform",
  :Pool
  "[:A_Dipper :Dollar :Hoot1 :Hoot2 :Hoot3 :Hoot4 :Ipsum_1Dipper :S-Foo-FootLbs]",
  :S1 0,
  :DurationDistribution "from-data",
  :S2 1000,
  :D1 "nil",
  :D2 "nil",
  :D3 "nil",
  :Rule "GetHoot"}
 {:S3 "nil",
  :Frequency 2,
  :StartDistribution "uniform",
  :Pool "{:A_Dipper 0.25 :Hoot1 0.25 :Hoot3 0.50}",
  :S1 0,
  :DurationDistribution "from-data",
  :S2 1000,
  :D1 "nil",
  :D2 "nil",
  :D3 "nil",
  :Rule "GetHootCumulative"}
 {:S3 "nil",
  :Frequency 20,
  :StartDistribution "uniform",
  :Pool
  "[:Dollar :Ipsum1_Dipper :Some16 :Some18 :Some21 :Some5 :Some6]",
  :S1 0,
  :DurationDistribution "from-data",
  :S2 1000,
  :D1 "nil",
  :D2 "nil",
  :D3 "nil",
  :Rule "RandomNonHoots"}
 {:S3 "nil",
  :Frequency 1,
  :StartDistribution "from-data",
  :Pool "[:every :Some5 :Some16]",
  :S1 "nil",
  :DurationDistribution "from-data",
  :S2 "nil",
  :D1 "nil",
  :D2 "nil",
  :D3 "nil",
  :Rule "Static"}]
)

(def rule-records2
  [{:S3 "nil",
  :Frequency 2,
  :StartDistribution "uniform",
  :Pool
  "[:Some5 :Some16]",
  :S1 0,
  :DurationDistribution "uniform",
  :S2 1000,
  :D1 1,
  :D2 100,
  :D3 "nil",
  :Rule "GetHoot"}]
)

;a list of notional demand records, primarily for use with
;helmet.
(def demand-records
[{:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "A 0-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 17.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "A 0",
  :StochasticDuration "",
  :DemandGroup "A",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "A 1-Initial",
  :StartDay 18.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 43.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "A 1",
  :StochasticDuration "",
  :DemandGroup "A",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "A 1-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 13.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "A 1",
  :StochasticDuration "",
  :DemandGroup "A",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "A 2",
  :StartDay 74.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 40.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "A 2",
  :StochasticDuration "",
  :DemandGroup "A",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "A 3",
  :StartDay 114.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 32.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "A 3",
  :StochasticDuration "",
  :DemandGroup "A",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "A 4",
  :StartDay 146.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 368.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "A 4",
  :StochasticDuration "",
  :DemandGroup "A",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 3.0,
  :Operation "A 5",
  :StartDay 514.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 88.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "A 5",
  :StochasticDuration "",
  :DemandGroup "A",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "A 6",
  :StartDay 602.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 280.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "A 6",
  :StochasticDuration "",
  :DemandGroup "A",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "A-Pi-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "A-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "A-Pi-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "A_Dipper",
  :Duration 84.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "A-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Dracula-1-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Dollar",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "Dracula-1",
  :StochasticDuration "",
  :DemandGroup "Dollar",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Dracula-1-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Dollar",
  :Duration 3180.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Dracula-1",
  :StochasticDuration "",
  :DemandGroup "Dollar",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Dracula-2-Saucy",
  :StartDay 3241.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Dollar",
  :Duration 1440.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Dracula-2",
  :StochasticDuration "",
  :DemandGroup "Dollar",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Ipsum1 1-Initial",
  :StartDay 49.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Ipsum1_Dipper",
  :Duration 12.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "Ipsum1 1",
  :StochasticDuration "",
  :DemandGroup "Ipsum1",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Ipsum1 1-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Ipsum1_Dipper",
  :Duration 61.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Ipsum1 1",
  :StochasticDuration "",
  :DemandGroup "Ipsum1",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "Ipsum1 2",
  :StartDay 122.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Ipsum1_Dipper",
  :Duration 64.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Ipsum1 2",
  :StochasticDuration "",
  :DemandGroup "Ipsum1",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "Ipsum1 3",
  :StartDay 186.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Ipsum1_Dipper",
  :Duration 48.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Ipsum1 3",
  :StochasticDuration "",
  :DemandGroup "Ipsum1",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "Ipsum1 6",
  :StartDay 234.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Ipsum1_Dipper",
  :Duration 112.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Ipsum1 6",
  :StochasticDuration "",
  :DemandGroup "Ipsum1",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Ipsum-1-Pi-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Ipsum1_Dipper",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "Ipsum1-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Ipsum1-Pi-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Ipsum1_Dipper",
  :Duration 125.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "Ipsum1-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "S-Foo-FootLbs-Lambda-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "S-Foo-FootLbs",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "S-Foo-FootLbs-Lambda",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Lambda",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "S-Foo-FootLbs-Lambda-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "S-Foo-FootLbs",
  :Duration 480.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "S-Foo-FootLbs-Lambda",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Lambda",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "S-Foo-FootLbs-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "S-Foo-FootLbs",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "S-Foo-FootLbs-Lambda",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "S-Foo-FootLbs-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "S-Foo-FootLbs",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "S-Foo-FootLbs-Lambda",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "S-Foo-FootLbs-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "S-Foo-FootLbs",
  :Duration 120.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "S-Foo-FootLbs-Lambda",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "S-Foo-FootLbs-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "S-Foo-FootLbs",
  :Duration 120.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "S-Foo-FootLbs-Lambda",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "Hoot1-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot1",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "Hoot1",
  :StochasticDuration "",
  :DemandGroup "Hoot1",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "Hoot1-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot1",
  :Duration 480.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot1",
  :StochasticDuration "",
  :DemandGroup "Hoot1",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot1-Pi-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot1",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "Hoot1-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot1-Pi-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot1",
  :Duration 125.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "Hoot1-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot2-Pi-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot2",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "Hoot2-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot2-Pi-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot2",
  :Duration 390.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "Hoot2-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 1.0,
  :Operation "Hoot3 PH II-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 16.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "Hoot3",
  :StochasticDuration "",
  :DemandGroup "Hoot3",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot3 1-Initial",
  :StartDay 17.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 44.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "Hoot3",
  :StochasticDuration "",
  :DemandGroup "Hoot3",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot3 1-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 12.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot3",
  :StochasticDuration "",
  :DemandGroup "Hoot3",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 5.0,
  :Operation "Hoot3 2",
  :StartDay 73.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 40.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot3",
  :StochasticDuration "",
  :DemandGroup "Hoot3",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 5.0,
  :Operation "Hoot3 3",
  :StartDay 113.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 32.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot3",
  :StochasticDuration "",
  :DemandGroup "Hoot3",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 5.0,
  :Operation "Hoot3 PHIVaa",
  :StartDay 145.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 336.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot3",
  :StochasticDuration "",
  :DemandGroup "Hoot3",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "Hoot3 PHIVab",
  :StartDay 481.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 256.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot3",
  :StochasticDuration "",
  :DemandGroup "Hoot3",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot3 6",
  :StartDay 737.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 240.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot3",
  :StochasticDuration "",
  :DemandGroup "Hoot3",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot3-Pi-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "Hoot3-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot3-Pi-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot3",
  :Duration 84.0,
  :Include "true",
  :SourceFirst "Mixture-Foo-Hoot",
  :Vignette "Hoot3-Pi",
  :StochasticDuration "",
  :DemandGroup "Foo",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "Pi",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot4 0-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot4",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "Hoot4",
  :StochasticDuration "",
  :DemandGroup "Hoot4",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot4 0-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot4",
  :Duration 337.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot4",
  :StochasticDuration "",
  :DemandGroup "Hoot4",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 4.0,
  :Operation "Hoot4 1 ",
  :StartDay 398.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot4",
  :Duration 1152.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot4",
  :StochasticDuration "",
  :DemandGroup "Hoot4",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "Hoot",
  :Quantity 2.0,
  :Operation "Hoot4 2",
  :StartDay 1550.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Hoot4",
  :Duration 740.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Hoot4",
  :StochasticDuration "",
  :DemandGroup "Hoot4",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some5-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some5",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "Some5",
  :StochasticDuration "",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some5-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some5",
  :Duration 1470.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Some5",
  :StochasticDuration "Yes",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some6-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some6",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "Some6",
  :StochasticDuration "",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some6-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some6",
  :Duration 570.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "Some6",
  :StochasticDuration "Yes",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some16-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some16",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "some16",
  :StochasticDuration "",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some16-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some16",
  :Duration 1020.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "some16",
  :StochasticDuration "Yes",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some18-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some18",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "some18",
  :StochasticDuration "",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some18-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some18",
  :Duration 120.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "some18",
  :StochasticDuration "Yes",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some21-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some21",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "some21",
  :StochasticDuration "",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some21-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some21",
  :Duration 210.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "some21",
  :StochasticDuration "Yes",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some21-Initial",
  :StartDay 1.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some21",
  :Duration 60.0,
  :Include "true",
  :SourceFirst "Mixture-Initial",
  :Vignette "some21",
  :StochasticDuration "",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}
 {:IsHoot "",
  :Quantity 1.0,
  :Operation "Some21-Saucy",
  :StartDay 61.0,
  :SRC "Widget-A",
  :Priority 1.0,
  :Group "Some21",
  :Duration 390.0,
  :Include "true",
  :SourceFirst "Mixture-Saucy",
  :Vignette "some21",
  :StochasticDuration "Yes",
  :DemandGroup "Beta",
  :Is1_8 0.0,
  :Type  "DemandRecord",
  :Category "Saucy",
  :OITitle "The Widget",
  :Enabled "true",
  :DependencyClass "",
  :Overlap 45.0,
  :DemandIndex 1.0}])

